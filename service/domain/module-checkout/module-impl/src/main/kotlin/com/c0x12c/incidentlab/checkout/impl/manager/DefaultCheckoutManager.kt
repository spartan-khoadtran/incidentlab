package com.c0x12c.incidentlab.checkout.impl.manager

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.c0x12c.incidentlab.checkout.api.CheckoutManager
import com.c0x12c.incidentlab.checkout.api.OrderRepository
import com.c0x12c.incidentlab.checkout.api.ProductRepository
import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.checkout.impl.cache.ProductCache
import com.c0x12c.incidentlab.checkout.impl.db.DbSessions
import com.c0x12c.incidentlab.checkout.impl.db.PoolTimeoutException
import com.c0x12c.incidentlab.checkout.impl.db.transaction
import com.c0x12c.incidentlab.checkout.impl.events.OrderEvents
import com.c0x12c.incidentlab.checkout.impl.leak.MemoryLeak
import com.c0x12c.incidentlab.checkout.impl.pricing.DownstreamException
import com.c0x12c.incidentlab.checkout.impl.pricing.PricingClient
import com.c0x12c.incidentlab.checkout.impl.runtime.HeapPressure
import com.c0x12c.incidentlab.checkout.impl.runtime.LegacyOrderCache
import com.c0x12c.incidentlab.checkout.impl.runtime.SupportTrace
import com.c0x12c.incidentlab.client.dto.checkout.CheckoutReceipt
import com.c0x12c.incidentlab.client.dto.checkout.OrderEvent
import com.c0x12c.incidentlab.client.dto.checkout.ProductDetail
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.infra.runtime.EventLoopRuntime
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.shared.exception.CheckoutError
import com.c0x12c.incidentlab.shared.exception.ClientException
import com.c0x12c.incidentlab.tracing.span
import com.c0x12c.incidentlab.utility.CpuBurner
import com.c0x12c.incidentlab.utility.jitter
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Tracer
import java.sql.Connection
import java.time.Duration
import kotlin.random.Random

/**
 * The checkout flow with every fault hook the api exercises use. The healthy path is: prices on
 * one short session, pricing called with no connection held, the order written on a second short
 * session, one event produced. The v2.31 path holds one session and one open transaction across
 * the pricing calls, which is what the pool exercise detects.
 */
class DefaultCheckoutManager(
  private val faults: Faults,
  private val products: ProductRepository,
  private val orders: OrderRepository,
  private val sessions: DbSessions,
  private val pricing: PricingClient,
  private val cache: ProductCache,
  private val events: OrderEvents,
  private val leak: MemoryLeak,
  private val heap: HeapPressure,
  private val legacyCache: LegacyOrderCache,
  private val supportTrace: SupportTrace,
  private val runtime: EventLoopRuntime,
  private val tracer: Tracer,
  private val objectMapper: ObjectMapper,
  registry: MeterRegistry
) : CheckoutManager {

  private val cacheRequests = LabCounter(registry, "cache_requests", "Cache lookups", listOf("result"))
  private val ordersPlaced = LabCounter(registry, "orders", "Orders placed", listOf("tenant"))

  override fun product(id: Int): Either<ClientException, ProductDetail> {
    leak.leak(faults.int(CheckoutFaults.LEAK_KB))
    runtimeFaults("/products/{id}", id)
    val burn = faults.double(CheckoutFaults.CPU_BURN_MS)
    if (burn > 0) {
      tracer.span("render.product_page") {
        runtime.onEventLoop { CpuBurner.burn(jitter(burn, RENDER_JITTER)) }
      }
    }
    val ttl = faults.int(CheckoutFaults.CACHE_TTL_MS).coerceAtLeast(1)
    val key = "product:$ttl:$id"
    cache.get(key)?.let { cached ->
      cacheRequests.increment("hit")
      return objectMapper.readValue(cached, ProductDetail::class.java).right()
    }
    cacheRequests.increment("miss")
    val detail = try {
      sessions.session("product_detail") { connection -> products.productDetail(connection, id) }
    } catch (e: PoolTimeoutException) {
      return CheckoutError.POOL_TIMEOUT.asException().left()
    }
    if (detail == null) {
      return CheckoutError.PRODUCT_NOT_FOUND.asException().left()
    }
    cache.set(key, objectMapper.writeValueAsString(detail), Duration.ofMillis(ttl.toLong()))
    return detail.right()
  }

  override fun checkout(tenant: String, items: List<Int>): Either<ClientException, CheckoutReceipt> {
    val basket = items.take(MAX_ITEMS).ifEmpty { listOf(Random.nextInt(1, CATALOG_TAIL + 1)) }
    leak.leak(faults.int(CheckoutFaults.LEAK_KB))
    runtimeFaults("/checkout", tenant)
    val receipt = try {
      if (faults.bool(CheckoutFaults.RECOMMEND_IN_TXN)) {
        checkoutHoldingTheConnection(tenant, basket)
      } else {
        checkoutWithShortSessions(tenant, basket)
      }
    } catch (e: PoolTimeoutException) {
      return CheckoutError.POOL_TIMEOUT.asException().left()
    } catch (e: DownstreamException) {
      return CheckoutError.PRICING_FAILED.asException(e.message).left()
    }
    ordersPlaced.increment(tenant)
    val event = OrderEvent(
      orderId = receipt.orderId,
      tenant = tenant,
      total = receipt.total,
      createdMs = System.currentTimeMillis(),
      source = "checkout"
    )
    events.produce(event, source = "checkout", key = receipt.orderId.toString())
    return receipt.right()
  }

  private fun checkoutWithShortSessions(tenant: String, items: List<Int>): CheckoutReceipt {
    val pinned = faults.bool(CheckoutFaults.PIN_DB)
    val prices = legacy(pinned) {
      sessions.session("load_prices") { connection -> loadPrices(connection, items) }
    }
    val total = pricing.price(items, prices).total
    val orderId = legacy(pinned) {
      val id = sessions.session("place_order") { connection ->
        connection.transaction { writeOrder(connection, tenant, items, total) }
      }
      if (pinned) {
        val hold = jitter(faults.double(CheckoutFaults.PIN_HOLD_MS), PIN_HOLD_JITTER)
        tracer.span("order_cache.put (synchronized)") { legacyCache.put(hold) }
      }
      id
    }
    return CheckoutReceipt(orderId = orderId, total = total)
  }

  private fun <T> legacy(pinned: Boolean, block: () -> T): T = if (pinned) legacyCache.locked(block) else block()

  private fun runtimeFaults(route: String, key: Any) {
    heap.write(faults.int(CheckoutFaults.RETAIN_MB), faults.int(CheckoutFaults.CHURN_KB))
    val lines = faults.int(CheckoutFaults.DEBUG_LOG_LINES)
    if (lines > 0) {
      supportTrace.write(lines, route, key)
    }
  }

  // v2.31: product suggestions added to checkout, inside the same session and transaction.
  private fun checkoutHoldingTheConnection(tenant: String, items: List<Int>): CheckoutReceipt =
    sessions.session("checkout") { connection ->
      connection.transaction {
        val prices = loadPrices(connection, items)
        val total = pricing.price(items, prices).total
        pricing.recommend(items)
        val orderId = writeOrder(connection, tenant, items, total)
        CheckoutReceipt(orderId = orderId, total = total)
      }
    }

  private fun loadPrices(connection: Connection, items: List<Int>): Map<Int, Double> {
    val prices = queryPrices(connection, items)
    val work = faults.double(CheckoutFaults.PRICE_WORK_MS)
    if (work > 0) {
      tracer.span("prices.compute (connection held)") {
        Thread.sleep(jitter(work, PRICE_WORK_JITTER).toMillis())
      }
    }
    return prices
  }

  private fun queryPrices(connection: Connection, items: List<Int>): Map<Int, Double> {
    val repeats = faults.int(CheckoutFaults.N_PLUS_ONE)
    if (repeats <= 0) {
      return products.prices(connection, items)
    }
    // What an ORM with lazy loading tends to do: one query per item, and then again.
    val prices = mutableMapOf<Int, Double>()
    for (id in items) {
      repeat(repeats) {
        products.priceById(connection, id)?.let { (productId, price) -> prices[productId] = price }
      }
    }
    return prices
  }

  private fun writeOrder(connection: Connection, tenant: String, items: List<Int>, total: Double): Long {
    val orderId = orders.insertOrder(connection, tenant, total)
    orders.insertItems(connection, orderId, items)
    if (faults.bool(CheckoutFaults.HOT_ROW)) {
      orders.reserveInventory(connection)
      val hold = faults.double(CheckoutFaults.LOCK_HOLD_MS)
      if (hold > 0) {
        tracer.span("inventory.reserve (row lock held)") {
          Thread.sleep(jitter(hold, LOCK_HOLD_JITTER).toMillis())
        }
      }
    }
    return orderId
  }

  private companion object {
    const val MAX_ITEMS = 10
    const val CATALOG_TAIL = 500
    const val RENDER_JITTER = 0.2
    const val LOCK_HOLD_JITTER = 0.3
    const val PRICE_WORK_JITTER = 0.3
    const val PIN_HOLD_JITTER = 0.3
  }
}

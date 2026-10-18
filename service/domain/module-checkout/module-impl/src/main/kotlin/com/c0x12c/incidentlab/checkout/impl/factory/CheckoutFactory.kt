package com.c0x12c.incidentlab.checkout.impl.factory

import com.c0x12c.incidentlab.checkout.api.CheckoutManager
import com.c0x12c.incidentlab.checkout.api.OrderRepository
import com.c0x12c.incidentlab.checkout.api.ProductRepository
import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.checkout.impl.cache.ProductCache
import com.c0x12c.incidentlab.checkout.impl.config.CheckoutConfig
import com.c0x12c.incidentlab.checkout.impl.db.DbSessions
import com.c0x12c.incidentlab.checkout.impl.events.OrderEvents
import com.c0x12c.incidentlab.checkout.impl.inventory.InventoryCache
import com.c0x12c.incidentlab.checkout.impl.leak.MemoryLeak
import com.c0x12c.incidentlab.checkout.impl.manager.DefaultCheckoutManager
import com.c0x12c.incidentlab.checkout.impl.pricing.ConnectionCounter
import com.c0x12c.incidentlab.checkout.impl.pricing.KeepAliveSwitch
import com.c0x12c.incidentlab.checkout.impl.pricing.PricingClient
import com.c0x12c.incidentlab.checkout.impl.pricing.PricingTimeoutInterceptor
import com.c0x12c.incidentlab.checkout.impl.render.ReceiptRenderer
import com.c0x12c.incidentlab.checkout.impl.resource.ApiGates
import com.c0x12c.incidentlab.client.PricingApi
import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.infra.config.AppKafkaConfig
import com.c0x12c.incidentlab.infra.runtime.EventLoopRuntime
import com.c0x12c.incidentlab.tracing.TraceContextInterceptor
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import jakarta.inject.Singleton
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.redisson.config.Config
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

// Bean wiring for the checkout vertical. @Factory methods construct the plain classes; the classes
// themselves are never annotated @Singleton. The repositories come from module-repository.
@Factory
class CheckoutFactory {

  @Singleton
  fun faults(): Faults = Faults(CheckoutFaults.DEFAULTS)

  @Singleton
  fun memoryLeak(registry: MeterRegistry): MemoryLeak = MemoryLeak(registry)

  @Singleton
  fun receiptRenderer(objectMapper: ObjectMapper): ReceiptRenderer = ReceiptRenderer(objectMapper)

  @Singleton
  @Bean(preDestroy = "shutdown")
  fun redissonClient(config: CheckoutConfig): RedissonClient =
    Redisson.create(
      Config().apply {
        useSingleServer().apply {
          address = "redis://${config.redisHost}:${config.redisPort}"
          connectTimeout = REDIS_TIMEOUT_MS
          timeout = REDIS_TIMEOUT_MS
          retryAttempts = REDIS_RETRIES
          codec = StringCodec.INSTANCE
        }
      }
    )

  @Singleton
  fun productCache(redisson: RedissonClient, tracer: Tracer): ProductCache = ProductCache(redisson, tracer)

  @Singleton
  fun pricingApi(config: CheckoutConfig, faults: Faults, openTelemetry: OpenTelemetry, objectMapper: ObjectMapper, registry: MeterRegistry): PricingApi {
    val pooled = OkHttpClient.Builder()
      .addInterceptor(PricingTimeoutInterceptor(faults))
      .addInterceptor(TraceContextInterceptor(openTelemetry))
      .eventListener(ConnectionCounter(registry, PRICING_TARGET))
      .connectionPool(ConnectionPool(POOL_CONNECTIONS, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
      .dispatcher(
        Dispatcher().apply {
          maxRequests = MAX_REQUESTS
          maxRequestsPerHost = MAX_REQUESTS
        }
      )
      .retryOnConnectionFailure(false)
      .build()
    return Retrofit.Builder()
      .baseUrl(config.pricingUrl.trimEnd('/') + "/")
      .callFactory(KeepAliveSwitch(faults, pooled))
      .addConverterFactory(JacksonConverterFactory.create(objectMapper))
      .build()
      .create(PricingApi::class.java)
  }

  @Singleton
  fun pricingClient(api: PricingApi, tracer: Tracer, registry: MeterRegistry): PricingClient = PricingClient(api, tracer, registry)

  @Singleton
  fun dbSessions(db: DatabaseContext, faults: Faults, gates: ApiGates, tracer: Tracer, registry: MeterRegistry): DbSessions =
    DbSessions(db = db, faults = faults, gates = gates, tracer = tracer, registry = registry)

  @Singleton
  @Bean(preDestroy = "close")
  fun orderEvents(
    kafka: AppKafkaConfig,
    openTelemetry: OpenTelemetry,
    tracer: Tracer,
    objectMapper: ObjectMapper,
    registry: MeterRegistry
  ): OrderEvents = OrderEvents(kafka, openTelemetry, tracer, objectMapper, registry).also { it.start() }

  @Singleton
  fun inventoryCache(faults: Faults, tracer: Tracer, registry: MeterRegistry): InventoryCache = InventoryCache(faults, tracer, registry)

  @Singleton
  fun checkoutManager(
    faults: Faults,
    products: ProductRepository,
    orders: OrderRepository,
    sessions: DbSessions,
    pricing: PricingClient,
    cache: ProductCache,
    events: OrderEvents,
    inventory: InventoryCache,
    leak: MemoryLeak,
    renderer: ReceiptRenderer,
    runtime: EventLoopRuntime,
    tracer: Tracer,
    objectMapper: ObjectMapper,
    registry: MeterRegistry
  ): CheckoutManager = DefaultCheckoutManager(
    faults = faults,
    products = products,
    orders = orders,
    sessions = sessions,
    pricing = pricing,
    cache = cache,
    events = events,
    inventory = inventory,
    leak = leak,
    renderer = renderer,
    runtime = runtime,
    tracer = tracer,
    objectMapper = objectMapper,
    registry = registry
  )

  private companion object {
    const val REDIS_TIMEOUT_MS = 2_000
    const val REDIS_RETRIES = 1
    const val PRICING_TARGET = "pricing"
    const val POOL_CONNECTIONS = 512
    const val POOL_KEEP_ALIVE_MINUTES = 5L
    const val MAX_REQUESTS = 4_096
  }
}

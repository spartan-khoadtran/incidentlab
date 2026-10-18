package com.c0x12c.incidentlab.loadgen.generator

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabGauge
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.Propagation
import com.c0x12c.incidentlab.tracing.markError
import com.c0x12c.incidentlab.loadgen.factory.LoadgenConfig
import com.c0x12c.incidentlab.loadgen.factory.LoadgenFaults
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.random.Random
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Open-model load: every tenant has its own Poisson arrival process, so a slow system does not
 * slow the arrivals down. Each logical user request may send several attempts when the retry fault
 * is on; the metrics keep the two apart, which is the retry-storm exercise. The root span of every
 * trace starts here, so this service makes the sampling decision for the whole system.
 *
 * With `closed_clients` above 0 the open arrivals stop and that many closed-loop clients run
 * instead: each one waits for its answer before it sends the next request. Same mix, same metrics.
 */
@Singleton
class LoadGenerator(
  private val config: LoadgenConfig,
  private val faults: Faults,
  private val openTelemetry: OpenTelemetry,
  private val tracer: Tracer,
  registry: MeterRegistry
) : ApplicationEventListener<StartupEvent>, Logging {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("loadgen"))

  private val requests = LabCounter(registry, "client_requests", "Logical user requests (after retries)", listOf("endpoint", "tenant", "outcome"))
  private val attempts = LabCounter(registry, "client_attempts", "HTTP attempts actually sent (retries included)", listOf("endpoint", "outcome"))
  private val duration = LabHistogram(registry, "client_duration_seconds", "What the user waits for, retries included", LatencyBuckets.LATENCY, listOf("endpoint", "outcome"))
  private val targetRps = LabGauge(registry, "client_target_rps", "Configured arrival rate", listOf("tenant"))
  private val dropped: Counter = Counter.builder("client_dropped").description("Arrivals dropped because too many requests were in flight").register(registry)
  private val inflight = AtomicInteger().also { counter ->
    Gauge.builder("client_inflight", counter) { it.get().toDouble() }.description("Requests waiting for an answer").register(registry)
  }

  private val closedClients = AtomicInteger().also { counter ->
    Gauge.builder("client_closed_clients", counter) { it.get().toDouble() }
      .description("Closed-loop clients asked for; 0 means the open model with Poisson arrivals")
      .register(registry)
  }

  private val closedLoop = ClosedLoop(scope) { closedRequest() }

  private val crawlCursor = AtomicInteger()

  private val http = OkHttpClient.Builder()
    .dispatcher(
      Dispatcher().apply {
        maxRequests = MAX_INFLIGHT
        maxRequestsPerHost = MAX_INFLIGHT
      }
    )
    .connectionPool(ConnectionPool(POOL_CONNECTIONS, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
    .retryOnConnectionFailure(false)
    .build()

  override fun onApplicationEvent(event: StartupEvent) {
    start()
  }

  fun start(): Job =
    scope.launch {
      delay(config.startDelay.toMillis())
      info { "load generator started [target=${config.targetUrl}]" }
      generate()
    }

  @PreDestroy
  fun stop() {
    scope.cancel()
    http.dispatcher.executorService.shutdown()
  }

  private suspend fun generate() {
    val nextAt = mutableMapOf<Arrival, Double>()
    while (scope.isActive) {
      val now = System.nanoTime() / NANOS_PER_SECOND
      val closed = faults.int(LoadgenFaults.CLOSED_CLIENTS).coerceIn(0, MAX_CLOSED_CLIENTS)
      closedClients.set(closed)
      closedLoop.resize(closed)
      val rates = currentRates().mapValues { (_, rate) -> if (closed > 0) 0.0 else rate }
      for (tenant in rates.keys.map { it.tenant }.toSet()) {
        targetRps.set(rates.filterKeys { it.tenant == tenant }.values.sum(), tenant)
      }
      for ((arrival, rate) in rates) {
        if (rate <= 0) {
          nextAt.remove(arrival)
          continue
        }
        val scheduled = nextAt[arrival]
        if (scheduled == null || scheduled < now - 1.0) {
          nextAt[arrival] = now + exponential(rate)
        }
        while ((nextAt[arrival] ?: now) <= now) {
          if (inflight.get() >= MAX_INFLIGHT) {
            dropped.increment()
          } else {
            val share = if (arrival.crawl) 0.0 else faults.double(LoadgenFaults.CHECKOUT_SHARE)
            val endpoint = if (Random.nextDouble() < share) "checkout" else "products"
            scope.launch { logicalRequest(endpoint, arrival.tenant, arrival.crawl) }
          }
          nextAt[arrival] = (nextAt[arrival] ?: now) + exponential(rate)
        }
      }
      delay(TICK_MILLIS)
    }
  }

  private fun currentRates(): Map<Arrival, Double> {
    val multiplier = faults.double(LoadgenFaults.RATE_MULTIPLIER).takeIf { it > 0 } ?: 1.0
    val surge = faults.double(LoadgenFaults.SURGE_MULTIPLIER).takeIf { it > 0 } ?: 1.0
    val outage = faults.string(LoadgenFaults.TENANT_OUTAGE)
    val out = mutableMapOf<Arrival, Double>()
    for ((tenant, rate) in faults.map(LoadgenFaults.TENANTS)) {
      val rps = if (tenant == outage) 0.0 else (rate as? Number)?.toDouble() ?: 0.0
      out[Arrival(tenant, crawl = false)] = rps * multiplier * surge
    }
    for ((tenant, rate) in faults.map(LoadgenFaults.EXTRA_RPS)) {
      out[Arrival(tenant, crawl = true)] = (rate as? Number)?.toDouble() ?: 0.0
    }
    return out
  }

  private suspend fun closedRequest() {
    val share = faults.double(LoadgenFaults.CHECKOUT_SHARE)
    val endpoint = if (Random.nextDouble() < share) "checkout" else "products"
    logicalRequest(endpoint, pickTenant(), crawl = false)
  }

  private fun pickTenant(): String {
    val outage = faults.string(LoadgenFaults.TENANT_OUTAGE)
    val weights = faults.map(LoadgenFaults.TENANTS)
      .mapValues { (tenant, rate) -> if (tenant == outage) 0.0 else (rate as? Number)?.toDouble() ?: 0.0 }
      .filterValues { it > 0 }
    var draw = Random.nextDouble() * weights.values.sum()
    for ((tenant, weight) in weights) {
      draw -= weight
      if (draw < 0) {
        return tenant
      }
    }
    return weights.keys.lastOrNull() ?: DEFAULT_TENANT
  }

  private suspend fun logicalRequest(endpoint: String, tenant: String, crawl: Boolean) {
    inflight.incrementAndGet()
    val start = System.nanoTime()
    var outcome = "ok"
    val span = tracer.spanBuilder("user $endpoint").setSpanKind(SpanKind.CLIENT).setAttribute("tenant", tenant).startSpan()
    val context = Context.root().with(span)
    try {
      repeat(1 + faults.int(LoadgenFaults.RETRIES)) {
        outcome = oneAttempt(endpoint, tenant, crawl, context)
        attempts.increment(endpoint, outcome)
        if (outcome == "ok" || outcome == "http_4xx") {
          return
        }
      }
    } finally {
      if (outcome != "ok" && outcome != "http_4xx") {
        span.markError(outcome)
      }
      span.makeCurrent().use { duration.observe((System.nanoTime() - start) / NANOS_PER_SECOND, endpoint, outcome) }
      span.end()
      inflight.decrementAndGet()
      requests.increment(endpoint, tenant, outcome)
    }
  }

  private suspend fun oneAttempt(endpoint: String, tenant: String, crawl: Boolean, context: Context): String {
    var timeoutMs = faults.double(LoadgenFaults.TIMEOUT_MS)
    if (endpoint == "checkout" && faults.double(LoadgenFaults.CHECKOUT_TIMEOUT_MS) > 0) {
      timeoutMs = faults.double(LoadgenFaults.CHECKOUT_TIMEOUT_MS)
    }
    val headers = mutableMapOf("X-Tenant" to tenant)
    Propagation.inject(openTelemetry, headers, context)
    val builder = Request.Builder()
    headers.forEach { (key, value) -> builder.header(key, value) }
    val request = if (endpoint == "products") {
      builder.url("${config.targetUrl}/products/${pickProduct(crawl)}").get().build()
    } else {
      val items = List(Random.nextInt(1, MAX_BASKET + 1)) { pickProduct(false) }
      builder.url("${config.targetUrl}/checkout").post("""{"items":[${items.joinToString(",")}]}""".toRequestBody(JSON)).build()
    }
    val call = http.newBuilder().callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build().newCall(request)
    return try {
      call.await().use { response ->
        when {
          response.code >= SERVER_ERROR -> "http_5xx"
          response.code >= CLIENT_ERROR -> "http_4xx"
          else -> "ok"
        }
      }
    } catch (e: InterruptedIOException) {
      "timeout"
    } catch (e: IOException) {
      "conn_error"
    }
  }

  // A crawler walks the catalog in order, so it never meets a product it cached; users mostly browse the first 50.
  private fun pickProduct(crawl: Boolean): Int =
    when {
      crawl -> crawlCursor.getAndIncrement().mod(CATALOG_SIZE) + 1
      Random.nextDouble() < HOT_SHARE -> Random.nextInt(1, HOT_PRODUCTS + 1)
      else -> Random.nextInt(1, WARM_PRODUCTS + 1)
    }

  private fun exponential(rate: Double): Double = -ln(1 - Random.nextDouble()) / rate

  private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
          }
        }
      )
      continuation.invokeOnCancellation { cancel() }
    }

  private data class Arrival(val tenant: String, val crawl: Boolean)

  private companion object {
    const val MAX_INFLIGHT = 4_000
    const val MAX_CLOSED_CLIENTS = 500
    const val DEFAULT_TENANT = "web"
    const val POOL_CONNECTIONS = 1_000
    const val POOL_KEEP_ALIVE_MINUTES = 5L
    const val TICK_MILLIS = 5L
    const val MAX_BASKET = 4
    const val CATALOG_SIZE = 100_000
    const val HOT_SHARE = 0.8
    const val HOT_PRODUCTS = 50
    const val WARM_PRODUCTS = 500
    const val SERVER_ERROR = 500
    const val CLIENT_ERROR = 400
    const val NANOS_PER_SECOND = 1_000_000_000.0
    val JSON = "application/json".toMediaType()
  }
}

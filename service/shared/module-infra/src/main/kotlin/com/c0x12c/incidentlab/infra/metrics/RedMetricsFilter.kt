package com.c0x12c.incidentlab.infra.metrics

import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.core.order.Ordered
import io.micronaut.http.HttpAttributes
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ServerFilter
import io.micronaut.http.filter.FilterContinuation
import io.micronaut.http.filter.ServerFilterPhase
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import java.util.concurrent.atomic.AtomicInteger

/**
 * The RED metrics of every service: requests by route and status, duration by route and outcome,
 * and in-flight. Duration starts when this filter runs, so it excludes any queue a filter ahead of
 * it (the api's admission gate at FIRST) makes the request wait in, exactly like an APM agent that
 * only sees the handler. The load balancer in front sees the whole wait. It runs inside the server
 * span, so a latency bucket can carry the trace id of a sampled request as an exemplar.
 */
@ServerFilter("/**")
@ExecuteOn(TaskExecutors.BLOCKING)
class RedMetricsFilter(
  registry: MeterRegistry
) : Ordered {

  private val requests = LabCounter(registry, "http_server_requests", "HTTP requests handled by this service", listOf("route", "method", "status"))
  private val duration = LabHistogram(
    registry,
    "http_server_duration_seconds",
    "Time from when the handler starts (after any admission queue) to the response",
    LatencyBuckets.LATENCY,
    listOf("route", "outcome")
  )
  private val inflight = AtomicInteger().also { counter ->
    Gauge.builder("http_server_inflight", counter) { it.get().toDouble() }
      .description("Requests currently inside the handler")
      .register(registry)
  }

  override fun getOrder(): Int = ServerFilterPhase.TRACING.after()

  @RequestFilter
  fun filter(request: HttpRequest<*>, continuation: FilterContinuation<MutableHttpResponse<*>>): MutableHttpResponse<*> {
    if (SKIP_PREFIXES.any { request.path.startsWith(it) }) {
      return continuation.proceed()
    }
    val route = request.getAttribute(HttpAttributes.URI_TEMPLATE, String::class.java).orElse(UNMATCHED)
    val start = System.nanoTime()
    inflight.incrementAndGet()
    var status = INTERNAL_ERROR
    try {
      val response = continuation.proceed()
      status = response.status.code
      return response
    } finally {
      inflight.decrementAndGet()
      val elapsed = (System.nanoTime() - start) / NANOS_PER_SECOND
      requests.increment(route, request.methodName, status.toString())
      duration.observe(elapsed, route, if (status >= INTERNAL_ERROR) "error" else "ok")
    }
  }

  companion object {
    val SKIP_PREFIXES = listOf("/metrics", "/admin", "/health")
    const val UNMATCHED = "unmatched"
    private const val INTERNAL_ERROR = 500
    private const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

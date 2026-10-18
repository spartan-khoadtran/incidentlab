package com.c0x12c.incidentlab.checkout.impl.pricing

import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.client.PricingApi
import com.c0x12c.incidentlab.client.dto.pricing.PriceQuote
import com.c0x12c.incidentlab.client.dto.pricing.PriceRequest
import com.c0x12c.incidentlab.client.dto.pricing.RecommendRequest
import com.c0x12c.incidentlab.client.dto.pricing.Recommendation
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.markError
import com.c0x12c.incidentlab.tracing.span
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Call

class DownstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The api's view of pricing: a CLIENT span per call plus `http_client_*` by target, route and outcome. */
class PricingClient(
  private val api: PricingApi,
  private val tracer: Tracer,
  registry: MeterRegistry
) {

  private val requests = LabCounter(registry, "http_client_requests", "Calls to downstream HTTP services", listOf("target", "route", "outcome"))
  private val duration = LabHistogram(registry, "http_client_duration_seconds", "Downstream call time seen by the caller", LatencyBuckets.LATENCY, listOf("target", "route"))

  fun price(items: List<Int>, prices: Map<Int, Double>): PriceQuote = call("/price") { api.price(PriceRequest(items, prices)) }

  fun recommend(items: List<Int>): Recommendation = call("/recommend") { api.recommend(RecommendRequest(items)) }

  private fun <T> call(route: String, call: () -> Call<T>): T {
    val start = System.nanoTime()
    var outcome = "ok"
    try {
      return tracer.span("HTTP POST pricing$route", SpanKind.CLIENT, mapOf("peer.service" to TARGET, "http.route" to route)) { span ->
        try {
          val response = call().execute()
          if (response.code() >= SERVER_ERROR) {
            outcome = "http_5xx"
            span.markError("pricing ${response.code()}")
            throw DownstreamException("pricing $route -> ${response.code()}")
          }
          response.body() ?: throw DownstreamException("pricing $route -> ${response.code()} without a body")
        } catch (e: InterruptedIOException) {
          outcome = "timeout"
          throw DownstreamException("pricing $route timeout", e)
        } catch (e: IOException) {
          outcome = "conn_error"
          throw DownstreamException("pricing $route ${e::class.java.simpleName}", e)
        }
      }
    } finally {
      requests.increment(TARGET, route, outcome)
      duration.observe((System.nanoTime() - start) / NANOS_PER_SECOND, TARGET, route)
    }
  }

  private companion object {
    const val TARGET = "pricing"
    const val SERVER_ERROR = 500
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

/** Reads the pricing timeout fault per call, so the controller can shorten it without a restart. */
class PricingTimeoutInterceptor(private val faults: Faults) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val millis = (faults.double(CheckoutFaults.PRICING_TIMEOUT_S) * MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
    return chain
      .withConnectTimeout(millis, TimeUnit.MILLISECONDS)
      .withReadTimeout(millis, TimeUnit.MILLISECONDS)
      .proceed(chain.request())
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1_000.0
  }
}

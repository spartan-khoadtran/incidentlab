package com.c0x12c.incidentlab.labctl.bench

import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.fasterxml.jackson.annotation.JsonIgnore

/** One window at one layer. A null is a number Prometheus had no data for. */
data class LayerReading(
  val rate: Double?,
  val p50Seconds: Double?,
  val p99Seconds: Double?,
  val errorShare: Double?
)

data class WindowReading(
  val client: LayerReading,
  val api: LayerReading,
  val clientMeanSeconds: Double?,
  val apiInflight: Double?
) {
  @get:JsonIgnore
  val empty: Boolean
    get() = listOf(client.rate, client.p99Seconds, api.rate, api.p99Seconds, clientMeanSeconds, apiInflight).all { it == null }
}

/** Reads a window ending at `at` and `seconds` long, for the client (loadgen) and for api. */
class BenchMeasure(private val probe: LabProbe) {

  fun read(at: Double, seconds: Int): WindowReading {
    fun q(expr: String) = probe.instantQuery(expr, at)
    val w = "${seconds}s"
    return WindowReading(
      client = LayerReading(q(clientRate(w)), q(quantile(0.5, CLIENT, w)), q(quantile(0.99, CLIENT, w)), q(clientErrors(w))),
      api = LayerReading(q(apiRate(w)), q(quantile(0.5, API, w)), q(quantile(0.99, API, w)), q(apiErrors(w))),
      clientMeanSeconds = q(clientMean(w)),
      apiInflight = q(apiInflight(w))
    )
  }

  companion object {
    private const val CLIENT = "client_duration_seconds_bucket"
    private const val API = "http_server_duration_seconds_bucket{job=\"api\"}"

    fun clientRate(w: String): String = "sum(rate(client_requests_total[$w]))"

    fun clientErrors(w: String): String =
      "(sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[$w])) or vector(0)) / sum(rate(client_requests_total[$w]))"

    fun clientMean(w: String): String = "sum(rate(client_duration_seconds_sum[$w])) / sum(rate(client_duration_seconds_count[$w]))"

    fun apiRate(w: String): String = "sum(rate(http_server_requests_total{job=\"api\"}[$w]))"

    fun apiErrors(w: String): String =
      "(sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[$w])) or vector(0)) / sum(rate(http_server_requests_total{job=\"api\"}[$w]))"

    fun apiInflight(w: String): String = "avg_over_time(http_server_inflight{job=\"api\"}[$w])"

    fun quantile(q: Double, buckets: String, w: String): String = "histogram_quantile($q, sum by (le) (rate($buckets[$w])))"
  }
}

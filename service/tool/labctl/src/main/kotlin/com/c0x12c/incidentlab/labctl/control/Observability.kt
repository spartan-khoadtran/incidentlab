package com.c0x12c.incidentlab.labctl.control

import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** One evaluation of a PromQL expression. */
data class Sample(
  val at: Double,
  val value: Double
)

/** What an exercise reads from the running lab before it starts, and the marks it leaves on the dashboards. */
interface LabProbe {
  fun cpuCount(): Int?

  /** Mean cost of one uncached product query on this machine, used to calibrate load. */
  fun productDetailSeconds(): Double?

  /** loadgen's default rate per tenant, from its own fault defaults. */
  fun tenants(): Map<String, Int>?

  fun serviceStatus(): Map<String, String?>

  /** The first series of `expr` every `step` seconds from `start` to `end`, NaN points left out. Null when Prometheus does not answer. */
  fun rangeQuery(expr: String, start: Double, end: Double, step: Double): List<Sample>? = null

  /** The first series of `expr` at `at` (now when null). Null when Prometheus does not answer or has no data. */
  fun instantQuery(expr: String, at: Double? = null): Double? = null
}

fun interface Annotator {
  fun annotate(text: String, tags: List<String>, atSeconds: Double)
}

class HttpLabProbe(private val http: JsonHttp) : LabProbe {

  override fun cpuCount(): Int? =
    runCatching { http.get("${Services.ENDPOINTS.getValue("noisy")}/health").path("cpu_count").asInt() }.getOrNull()

  override fun productDetailSeconds(): Double? =
    instantQuery("sum(rate(pg_query_exec_seconds_total{query=\"product_detail\"}[10m])) / sum(rate(pg_query_calls_total{query=\"product_detail\"}[10m]))")
      ?.takeIf { it > MIN_PLAUSIBLE && it < MAX_PLAUSIBLE }

  override fun rangeQuery(expr: String, start: Double, end: Double, step: Double): List<Sample>? =
    runCatching {
      val url = "$PROMETHEUS/api/v1/query_range?query=${encode(expr)}&start=${seconds(start)}&end=${seconds(end)}&step=${seconds(step)}"
      http.get(url).path("data").path("result").path(0).path("values").mapNotNull { point ->
        point.path(1).asText().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Sample(point.path(0).asDouble(), it) }
      }
    }.getOrNull()

  override fun instantQuery(expr: String, at: Double?): Double? =
    runCatching {
      val url = "$PROMETHEUS/api/v1/query?query=${encode(expr)}" + (at?.let { "&time=${seconds(it)}" } ?: "")
      http.get(url).path("data").path("result").path(0).path("value").path(1).asText().toDoubleOrNull()?.takeIf { it.isFinite() }
    }.getOrNull()

  private fun encode(expr: String): String = URLEncoder.encode(expr, Charsets.UTF_8)

  private fun seconds(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

  override fun tenants(): Map<String, Int>? =
    runCatching {
      http.get("${Services.ENDPOINTS.getValue("loadgen")}/admin/faults").path("defaults").path("tenants")
        .properties().associate { (tenant, rate) -> tenant to rate.asInt() }
        .takeIf { it.isNotEmpty() }
    }.getOrNull()

  /** All services at once, so one that hangs costs one timeout, not one per service. */
  override fun serviceStatus(): Map<String, String?> =
    Executors.newVirtualThreadPerTaskExecutor().use { pool ->
      Services.ENDPOINTS
        .mapValues { (_, url) -> pool.submit<String?> { runCatching { http.get("$url/admin/faults") }.exceptionOrNull()?.javaClass?.simpleName } }
        .mapValues { (_, check) -> check.get() }
    }

  companion object {
    val PROMETHEUS: String = System.getenv("PROMETHEUS_URL") ?: "http://prometheus:9090"
    private const val MIN_PLAUSIBLE = 0.001
    private const val MAX_PLAUSIBLE = 0.2
  }
}

/** Writes annotations to Grafana. A failure is reported, not thrown: the CLI prints it, the daemon logs it. */
class GrafanaAnnotator(private val http: JsonHttp, private val report: (String) -> Unit) : Annotator {

  override fun annotate(text: String, tags: List<String>, atSeconds: Double) {
    try {
      http.post("$GRAFANA/api/annotations", mapOf("time" to (atSeconds * MILLIS_PER_SECOND).toLong(), "tags" to listOf("lab") + tags, "text" to text))
    } catch (e: Exception) {
      report("(could not write the annotation to Grafana: ${e.message})")
    }
  }

  companion object {
    val GRAFANA: String = System.getenv("GRAFANA_URL") ?: "http://grafana:3000"
    private const val MILLIS_PER_SECOND = 1_000.0
  }
}

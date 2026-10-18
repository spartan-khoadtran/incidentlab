package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.Terminal

/** What an exercise reads from the running lab before it starts, and the marks it leaves on the dashboards. */
interface LabProbe {
  fun cpuCount(): Int?

  /** Mean cost of one uncached product query on this machine, used to calibrate load. */
  fun productDetailSeconds(): Double?

  /** loadgen's default rate per tenant, from its own fault defaults. */
  fun tenants(): Map<String, Int>?

  fun serviceStatus(): Map<String, String?>
}

fun interface Annotator {
  fun annotate(text: String, tags: List<String>, atSeconds: Double)
}

class HttpLabProbe(private val http: JsonHttp) : LabProbe {

  override fun cpuCount(): Int? =
    runCatching { http.get("${Services.ENDPOINTS.getValue("noisy")}/health").path("cpu_count").asInt() }.getOrNull()

  override fun productDetailSeconds(): Double? =
    runCatching {
      val expr = "sum(rate(pg_query_exec_seconds_total{query=\"product_detail\"}[10m])) / sum(rate(pg_query_calls_total{query=\"product_detail\"}[10m]))"
      val value = http.get(PROMETHEUS + "/api/v1/query?query=" + java.net.URLEncoder.encode(expr, Charsets.UTF_8))
        .path("data").path("result").get(0).path("value").get(1).asText().toDouble()
      value.takeIf { it > MIN_PLAUSIBLE && it < MAX_PLAUSIBLE }
    }.getOrNull()

  override fun tenants(): Map<String, Int>? =
    runCatching {
      http.get("${Services.ENDPOINTS.getValue("loadgen")}/admin/faults").path("defaults").path("tenants")
        .properties().associate { (tenant, rate) -> tenant to rate.asInt() }
        .takeIf { it.isNotEmpty() }
    }.getOrNull()

  override fun serviceStatus(): Map<String, String?> =
    Services.ENDPOINTS.mapValues { (_, url) ->
      runCatching { http.get("$url/admin/faults") }.exceptionOrNull()?.javaClass?.simpleName
    }

  companion object {
    val PROMETHEUS: String = System.getenv("PROMETHEUS_URL") ?: "http://prometheus:9090"
    private const val MIN_PLAUSIBLE = 0.001
    private const val MAX_PLAUSIBLE = 0.2
  }
}

class GrafanaAnnotator(private val http: JsonHttp, private val terminal: Terminal) : Annotator {

  override fun annotate(text: String, tags: List<String>, atSeconds: Double) {
    try {
      http.post("$GRAFANA/api/annotations", mapOf("time" to (atSeconds * MILLIS_PER_SECOND).toLong(), "tags" to listOf("lab") + tags, "text" to text))
    } catch (e: Exception) {
      terminal.out("(could not write the annotation to Grafana: ${e.message})")
    }
  }

  companion object {
    val GRAFANA: String = System.getenv("GRAFANA_URL") ?: "http://grafana:3000"
    private const val MILLIS_PER_SECOND = 1_000.0
  }
}

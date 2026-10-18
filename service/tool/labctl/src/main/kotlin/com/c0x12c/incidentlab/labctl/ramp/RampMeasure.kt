package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.control.LabProbe
import kotlin.math.roundToLong

/** Reads one row per finished step from Prometheus: offered, achieved, p99 and the error share. */
class RampMeasure(private val probe: LabProbe) {

  /** Null when Prometheus did not answer. Each window ends at its step's end and skips the step's first seconds. */
  fun read(started: Double, every: Int, stepsRun: Int): List<StepReading>? {
    if (stepsRun == 0) return emptyList()
    val window = RampPlan.windowSeconds(every)
    val start = started + every
    val end = started + stepsRun * every
    val offered = series(offered(window), start, end, every) ?: return null
    val achieved = series(achieved(window), start, end, every) ?: return null
    val p99 = series(p99(window), start, end, every) ?: return null
    val errors = series(errorShare(window), start, end, every) ?: return null
    return (0 until stepsRun).map { index ->
      val at = (started + (index + 1) * every).roundToLong()
      StepReading(index, RampPlan.rps(index), offered[at], achieved[at], p99[at], errors[at])
    }
  }

  private fun series(expr: String, start: Double, end: Double, step: Int): Map<Long, Double>? =
    probe.rangeQuery(expr, start, end, step.toDouble())?.associate { it.at.roundToLong() to it.value }

  companion object {
    fun offered(window: Int): String = "sum(rate(client_requests_total[${window}s]))"

    fun achieved(window: Int): String = "sum(rate(http_server_requests_total{job=\"api\",status=~\"2..\"}[${window}s]))"

    fun p99(window: Int): String = "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[${window}s])))"

    fun errorShare(window: Int): String =
      "(sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[${window}s])) or vector(0)) / sum(rate(client_requests_total[${window}s]))"
  }
}

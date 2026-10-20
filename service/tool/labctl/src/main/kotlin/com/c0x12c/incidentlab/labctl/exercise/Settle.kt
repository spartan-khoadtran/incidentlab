package com.c0x12c.incidentlab.labctl.exercise

/** One check of the settle gate. [value] is null when Prometheus had no answer, and an unknown value never blocks a start. */
data class SettleCheck(
  val name: String,
  val ok: Boolean,
  val value: String?,
  val target: String
)

/**
 * Whether the lab is back to normal after the last exercise, so a new one starts from a clean
 * baseline. [gaveUp] is true once the wait passed its cap: a start is then allowed with a warning.
 */
data class SettleStatus(
  val settled: Boolean,
  val checks: List<SettleCheck>,
  val waitedSeconds: Double,
  val gaveUp: Boolean
)

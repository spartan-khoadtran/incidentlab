package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import kotlin.math.max
import kotlin.math.roundToInt

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

/**
 * Reads whether the lab is back to normal after the last exercise. A start waits for it, so the
 * next exercise's baseline holds no part of the fault before.
 */
class SettleGate(
  private val probe: LabProbe,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND }
) {

  fun status(state: LabState): SettleStatus {
    val at = now()
    val waited = at - (state.lastEnded ?: 0.0)
    val checks = listOfNotNull(
      state.lastEnded?.let { check("Time since the last exercise ended", waited, "at least ${QUIET_SECONDS.toInt()} s", ::seconds) { it >= QUIET_SECONDS } },
      query("Every service up since its last start", UPTIME, "at least ${UPTIME_SECONDS.toInt()} s", ::seconds) { it >= UPTIME_SECONDS },
      query("Kafka lag", LAG, "under ${LAG_SECONDS.toInt()} s", ::seconds) { it < LAG_SECONDS },
      query("Errors users see", ERRORS, "under ${ERROR_PERCENT.toInt()}%", { "${Format.plain(round(it))}%" }) { it < ERROR_PERCENT },
      steadyLatency(),
      query("Requests waiting inside api", WAITING, "under $WAITING_MAX", { Format.plain(round(it)) }) { it < WAITING_MAX },
      query("Memory the last fault kept in api", FAULT_BYTES, "0 MB", { "${Format.plain(round(it / BYTES_PER_MB))} MB" }) { it <= 0.0 }
    )
    val settled = checks.all { it.ok }
    return SettleStatus(settled, checks, max(0.0, waited), !settled && waited > CAP_SECONDS)
  }

  /** The refusal a start gets while the lab settles, or null when it may start. */
  fun refusal(state: LabState): Refusal? = status(state).takeUnless { it.settled || it.gaveUp }?.let { Refusal.LabSettling(it) }

  private fun steadyLatency(): SettleCheck {
    val name = "User p99 is steady"
    val target = "within ${((STEADY_RATIO - 1) * PERCENT).toInt()}% of the minute before"
    val current = probe.instantQuery(P99)
    val before = probe.instantQuery("$P99 offset 1m")
    if (current == null || before == null) return SettleCheck(name, true, null, target)
    val ok = max(current, before) < FAST_SECONDS || (current <= before * STEADY_RATIO && before <= current * STEADY_RATIO)
    return SettleCheck(name, ok, "${seconds(current)} (was ${seconds(before)})", target)
  }

  private fun query(name: String, expr: String, target: String, show: (Double) -> String, ok: (Double) -> Boolean): SettleCheck =
    probe.instantQuery(expr)?.takeIf { it.isFinite() }?.let { check(name, it, target, show, ok) } ?: SettleCheck(name, true, null, target)

  private fun check(name: String, value: Double, target: String, show: (Double) -> String, ok: (Double) -> Boolean): SettleCheck =
    SettleCheck(name, ok(value), show(value), target)

  private fun seconds(value: Double): String = "${Format.plain(round(value))} s"

  private fun round(value: Double): Double = (value * TENTHS).roundToInt() / TENTHS

  companion object {
    const val QUIET_SECONDS = 60.0
    const val UPTIME_SECONDS = 120.0
    const val LAG_SECONDS = 5.0
    const val ERROR_PERCENT = 1.0
    const val WAITING_MAX = 0.5
    const val STEADY_RATIO = 1.3
    const val FAST_SECONDS = 0.2
    const val CAP_SECONDS = 300.0
    private const val PERCENT = 100
    private const val TENTHS = 10.0
    private const val BYTES_PER_MB = 1_048_576.0
    private const val MILLIS_PER_SECOND = 1000.0
    const val UPTIME = "min(time() - process_start_time_seconds{job=~\"api|pricing|worker\"})"
    const val LAG = "max(kafka_consumer_lag_seconds)"
    const val ERRORS = "100 * (sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[1m])) or vector(0)) / sum(rate(client_requests_total[1m]))"
    const val P99 = "histogram_quantile(0.99, sum by (le) (rate(client_duration_seconds_bucket[1m])))"
    const val WAITING = "max(avg_over_time({__name__=~\"db_pool_waiting|app_admission_waiting\",job=\"api\"}[1m]))"
    const val FAULT_BYTES = "sum({__name__=~\"app_leaked_bytes|app_retained_bytes\",job=\"api\"})"
  }
}

/** The CLI side of the gate: wait, print what is still settling every few seconds, then let the start go ahead. */
class SettleWait(
  private val gate: SettleGate,
  private val store: StateStore,
  private val terminal: Terminal,
  private val sleep: (Long) -> Unit = Thread::sleep
) {

  fun await() {
    var status = gate.status(store.load())
    if (status.settled) return
    terminal.out("Waiting for the lab to settle after the last exercise, so the next baseline is clean...")
    while (!status.settled && !status.gaveUp) {
      terminal.out("  still settling: " + pending(status))
      sleep(POLL_MILLIS)
      status = gate.status(store.load())
    }
    if (status.gaveUp) {
      terminal.out("Gave up waiting after ${status.waitedSeconds.toInt()} s. Starting anyway. Still not normal: ${pending(status)}")
    } else {
      terminal.out("The lab is back to normal.")
    }
    terminal.out()
  }

  private fun pending(status: SettleStatus): String = status.checks.filterNot { it.ok }.joinToString("; ") { "${it.name} ${it.value} (want ${it.target})" }

  companion object {
    const val POLL_MILLIS = 5_000L
  }
}

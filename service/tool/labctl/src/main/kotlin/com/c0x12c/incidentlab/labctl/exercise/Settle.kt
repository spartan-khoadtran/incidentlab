package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.NormalWindow
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.util.concurrent.Executors
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
 * What a start keeps from the gate: where its clean baseline begins, the checks still off, and the
 * new [normal] for the next start, or null to keep the one before.
 */
data class SettleMark(
  val cleanFrom: Double?,
  val unsettled: List<String>,
  val normal: NormalWindow?
) {
  /** Where the new exercise's clean baseline starts, with [lastEnded] the end before it. */
  fun baselineFrom(started: Double, lastEnded: Double?): Double = ExerciseEngine.baselineFrom(started, cleanFrom ?: lastEnded)

  companion object {
    val NONE = SettleMark(cleanFrom = null, unsettled = emptyList(), normal = null)
  }
}

/** How a start meets the settle gate. */
sealed interface SettleEntry {
  /** Refused while the lab settles. */
  data object Wait : SettleEntry

  /** Starts at once and records the checks still off, as `--no-settle` does. */
  data object Skip : SettleEntry

  /** The CLI already waited: the start keeps the [status] that let it through. */
  data class Waited(val status: SettleStatus) : SettleEntry
}

/** A start the gate let through. Its mark judges the same status that let it through. */
fun interface SettlePass {
  fun mark(at: Double): SettleMark
}

/** The settle step every start takes. With no gate, as in `labctl api`, nothing waits and nothing is recorded. */
fun SettleGate?.admit(state: LabState, entry: SettleEntry): Outcome<SettlePass> {
  if (this == null) return Outcome.Done(SettlePass { SettleMark.NONE })
  val status = (entry as? SettleEntry.Waited)?.status ?: status(state)
  if (entry == SettleEntry.Wait && !status.settled && !status.gaveUp) return Outcome.Refused(Refusal.LabSettling(status))
  return Outcome.Done(SettlePass { at -> mark(state, at, status) })
}

/**
 * Reads whether the lab is back to normal after the last exercise. A start waits for it, so the
 * next exercise's baseline holds no part of the fault before. "Normal" is [LabState.normal], the
 * clean stretch before the last settled start, and it must hold for a whole window.
 */
class SettleGate(
  private val probe: LabProbe,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND }
) {

  /** A value that must stay under its limit for the whole window. [limit] gets the lab's normal value, or null when there is none. */
  private class ValueCheck(val name: String, val expr: String, val show: (Double) -> String, val limit: (Double?) -> Double?, val strict: Boolean = false) {
    fun off(value: Double, limit: Double): Boolean = if (strict) value >= limit else value > limit
  }

  /** [stuck] means already off 2 minutes ago and not falling since. A value that just went off is waited out. */
  private class Reading(val check: ValueCheck, val worst: Double?, val normal: Double?, val current: Double?, val past: Double?) {
    val limit: Double? = check.limit(normal)
    val ok: Boolean = worst == null || limit == null || !check.off(worst, limit)
    val stuck: Boolean = !ok && limit != null && current != null && past != null && check.off(past, limit) && current >= past * STILL_FALLING
  }

  /**
   * One wait after the end at [lastEnded], from [since] to the last check at [checked]. A give-up
   * holds while the wait goes on, so the poll that gave up and the start after it agree.
   * [downSince] is the first check that found a service with no answer for 2 minutes.
   */
  private class Wait(val lastEnded: Double?, val since: Double, val checked: Double, val downSince: Double?, val gaveUp: Boolean)

  @Volatile
  private var wait: Wait? = null

  private val values = listOf(
    ValueCheck("Kafka lag", LAG, ::seconds, { LAG_SECONDS }, strict = true),
    ValueCheck("Errors users see", ERRORS, { "${Format.plain(round(it))}%" }, { normal -> max(ERROR_PERCENT, (normal ?: 0.0) * MARGIN) }),
    ValueCheck("User p99", P99, ::seconds, { normal -> max(FAST_SECONDS, (normal ?: 0.0) * MARGIN) }),
    ValueCheck("Requests waiting inside api", WAITING, { Format.plain(round(it)) }, { normal -> max(WAITING_MAX, (normal ?: 0.0) * MARGIN) }),
    ValueCheck("api memory (RSS)", RSS, ::megabytes, { normal -> normal?.let { it + RSS_SLACK_BYTES } })
  )

  fun status(state: LabState): SettleStatus {
    val at = now()
    val waited = state.lastEnded?.let { at - it }
    val reference = state.normal
    val readings = parallel(values) { read(it, reference) }
    val (uptime, upLately) = parallel(listOf(UPTIME, UP_LATELY)) { probe.instantQuery(it) }
    val checks = listOfNotNull(
      waited?.let { SettleCheck("Time since the last exercise ended", it >= QUIET_SECONDS, seconds(it), "at least ${QUIET_SECONDS.toInt()} s") },
      SettleCheck("Every service up since its last start", uptime == null || uptime >= UPTIME_SECONDS, uptime?.let(::seconds), "at least ${UPTIME_SECONDS.toInt()} s")
    ) + readings.map { reading ->
      val limit = reading.limit
      SettleCheck(
        reading.check.name,
        reading.ok,
        reading.worst?.let { worst -> reading.check.show(worst) + (reading.normal?.let { " (normal ${reading.check.show(it)})" } ?: "") },
        limit?.let { "at most ${reading.check.show(it)} for ${WINDOW_SECONDS.toInt()} s" } ?: "any value (no normal yet)"
      )
    }
    val settled = checks.all { it.ok }
    val previous = wait?.takeIf { it.lastEnded == state.lastEnded && at - it.checked <= WAIT_GAP_SECONDS }
    val downSince = if (upLately == 0.0) previous?.downSince ?: at else null
    val downForGood = downSince != null && at - downSince >= DOWN_GRACE_SECONDS
    val restarting = !downForGood && uptime != null && uptime < UPTIME_SECONDS
    val timeWillFix = (waited != null && waited < QUIET_SECONDS) || restarting
    val failing = readings.filterNot { it.ok }
    val stuck = !timeWillFix && (downForGood || failing.isNotEmpty()) && failing.all { it.stuck }
    val since = previous?.since ?: at
    val gaveUp = !settled &&
      (previous?.gaveUp == true || at - since > HARD_CAP_SECONDS || (stuck && (waited == null || waited > STUCK_AFTER_SECONDS)))
    wait = if (settled) null else Wait(state.lastEnded, since, at, downSince, gaveUp)
    return SettleStatus(settled, checks, max(0.0, waited ?: 0.0), gaveUp)
  }

  /**
   * What a start at [at] keeps, judged on the [status] that let it through. Only a settled start
   * moves normal: a baseline that still held the fault would raise the limits, and the next start
   * would pass with the same fault. Normal never reaches back before the load last changed.
   */
  fun mark(state: LabState, at: Double, status: SettleStatus): SettleMark {
    val unsettled = status.checks.filterNot { it.ok }.map { it.name }
    val clean = cleanSince(state, at)
    val normal = clean?.takeIf { unsettled.isEmpty() }
      ?.let { NormalWindow(maxOf(it, at - NORMAL_SECONDS, (state.loadSince ?: 0.0) + LOAD_STEADY_SECONDS), at) }
      ?.takeIf { it.to - it.from >= MIN_NORMAL_SECONDS }
    return SettleMark(clean, unsettled, normal)
  }

  /**
   * Where a clean baseline starts for an exercise starting at [at]: just after the last moment a value
   * was off its normal, and never before the last exercise ended.
   */
  fun cleanSince(state: LabState, at: Double): Double? {
    val ended = state.lastEnded ?: return null
    val from = max(ended, at - ExerciseEngine.BASELINE_SECONDS - STEP_SECONDS)
    val lastBad = parallel(values) { check ->
      val limit = check.limit(state.normal?.let { probe.instantQuery(normal(check.expr, it)) }) ?: return@parallel null
      probe.rangeQuery(check.expr, from, at, STEP_SECONDS)?.lastOrNull { check.off(it.value, limit) }?.at
    }.filterNotNull().maxOrNull()
    return max(ended, lastBad?.let { it + STEP_SECONDS } ?: ended).coerceAtMost(at)
  }

  private fun read(check: ValueCheck, reference: NormalWindow?): Reading {
    val (worst, normal, current, past) = parallel(
      listOf(
        "max_over_time((${check.expr})[${WINDOW_SECONDS.toInt()}s:${STEP_SECONDS.toInt()}s])",
        reference?.let { normal(check.expr, it) },
        check.expr,
        "last_over_time((${check.expr})[1m:${STEP_SECONDS.toInt()}s] offset ${PROGRESS_SECONDS.toInt()}s)"
      )
    ) { expr -> expr?.let { probe.instantQuery(it)?.takeIf { value -> value.isFinite() } } }
    return Reading(check, worst, normal, current, past)
  }

  private fun normal(expr: String, window: NormalWindow): String = "avg_over_time(($expr)[${(window.to - window.from).toLong()}s:${STEP_SECONDS.toInt()}s] @ ${window.to.toLong()})"

  private fun <T, R> parallel(items: List<T>, work: (T) -> R): List<R> =
    Executors.newVirtualThreadPerTaskExecutor().use { pool -> items.map { item -> pool.submit<R> { work(item) } }.map { it.get() } }

  private fun seconds(value: Double): String = "${Format.plain(round(value))} s"

  private fun megabytes(value: Double): String = "${Format.plain(round(value / BYTES_PER_MB))} MB"

  private fun round(value: Double): Double = (value * TENTHS).roundToInt() / TENTHS

  companion object {
    const val QUIET_SECONDS = 60.0
    const val UPTIME_SECONDS = 120.0
    const val WINDOW_SECONDS = 120.0
    const val NORMAL_SECONDS = 300.0
    const val MIN_NORMAL_SECONDS = 60.0
    const val STEP_SECONDS = 15.0
    const val PROGRESS_SECONDS = 120.0
    const val LAG_SECONDS = 5.0
    const val ERROR_PERCENT = 1.0
    const val WAITING_MAX = 0.5
    const val FAST_SECONDS = 0.2
    const val MARGIN = 1.3
    const val RSS_SLACK_BYTES = 64.0 * 1_048_576
    const val STILL_FALLING = 0.95
    const val STUCK_AFTER_SECONDS = 180.0
    const val HARD_CAP_SECONDS = 1200.0
    const val WAIT_GAP_SECONDS = 60.0
    const val DOWN_GRACE_SECONDS = 60.0
    const val LOAD_STEADY_SECONDS = 75.0
    private const val TENTHS = 10.0
    private const val BYTES_PER_MB = 1_048_576.0
    private const val MILLIS_PER_SECOND = 1000.0
    const val UPTIME = "min((time() - process_start_time_seconds{job=~\"api|pricing|worker\"}) or (up{job=~\"api|pricing|worker\"} == 0))"
    const val UP_LATELY = "min(max_over_time(up{job=~\"api|pricing|worker\"}[2m]))"
    const val LAG = "max(kafka_consumer_lag_seconds)"
    const val ERRORS = "100 * (sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[1m])) or vector(0)) / sum(rate(client_requests_total[1m]))"
    const val P99 = "histogram_quantile(0.99, sum by (le) (rate(client_duration_seconds_bucket[1m])))"
    const val WAITING = "sum(avg_over_time(db_pool_waiting{job=\"api\"}[1m])) + sum(avg_over_time(app_admission_waiting{job=\"api\"}[1m]))"
    const val RSS = "max(process_resident_memory_bytes{job=\"api\"})"
  }
}

/** The CLI side of the gate: wait, print what is still settling every few seconds, then let the start go ahead. */
class SettleWait(
  private val gate: SettleGate,
  private val store: StateStore,
  private val terminal: Terminal,
  private val sleep: (Long) -> Unit = Thread::sleep
) {

  /** The status the wait ended on: settled, or given up. */
  fun await(): SettleStatus {
    var status = gate.status(store.load())
    if (status.settled) return status
    terminal.out("Waiting for the lab to settle after the last exercise, so the next baseline is clean...")
    var shown: List<String>? = null
    var polls = 0
    while (!status.settled && !status.gaveUp) {
      val names = status.checks.filterNot { it.ok }.map { it.name }
      if (names != shown || polls % REPRINT_POLLS == 0) {
        terminal.out("  still settling: " + pending(status))
        shown = names
      }
      sleep(POLL_MILLIS)
      polls++
      status = gate.status(store.load())
    }
    if (status.gaveUp) {
      terminal.out("Gave up waiting, ${status.waitedSeconds.toInt()} s after the last exercise ended. Starting anyway. Still not normal: ${pending(status)}")
    } else {
      terminal.out("The lab is back to normal.")
    }
    terminal.out()
    return status
  }

  private fun pending(status: SettleStatus): String = status.checks.filterNot { it.ok }.joinToString("; ") { "${it.name} ${it.value} (want ${it.target})" }

  companion object {
    const val POLL_MILLIS = 5_000L

    /** Same checks still waiting: say so again every 30 s, not on every poll. */
    const val REPRINT_POLLS = 6
  }
}

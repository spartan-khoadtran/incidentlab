package com.c0x12c.incidentlab.labctl.bench

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.exercise.ExerciseMode
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.exercise.Format.millis
import com.c0x12c.incidentlab.labctl.exercise.Format.percent
import com.c0x12c.incidentlab.labctl.exercise.Format.rps
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil

data class BenchOptions(
  val clients: Int = BenchCatalog.DEFAULT_CLIENTS,
  val seconds: Int = BenchCatalog.DEFAULT_SECONDS
)

/**
 * `make bench`: a closed-loop load test on the live lab, not scored. Afterwards it prints the
 * minutes before against the bench window, at the client and at api, and says why the two differ.
 * Printing over [BenchEngine].
 */
class BenchExercise(
  private val store: StateStore,
  probe: LabProbe,
  annotator: Annotator,
  private val terminal: Terminal,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault()
) : ExerciseMode {

  private val engine = BenchEngine(store, probe, annotator, now, zone)

  override val mode: String = MODE

  /** Starts a bench, or prints the report of the one that is over. */
  fun start(options: BenchOptions): Int {
    val state = store.load()
    val active = state.active
    if (active != null && active.mode == MODE) {
      return if (BenchCatalog.running(params(active))) report(state) else answer(state)
    }
    val view = when (val outcome = engine.start(options.clients, options.seconds)) {
      is Done -> outcome.value.active
      is Refused -> {
        terminal.out("An exercise is still open. Use `make answer`, `make reveal` or `make clear` first.")
        return 1
      }
    }
    Format.banner(terminal, "CLOSED-LOOP BENCH #${view.n}  ·  started at ${Format.hhmm(view.startedAt, zone)}")
    BenchEngine.intro(options.clients, options.seconds).forEach(terminal::out)
    terminal.out()
    terminal.out("What to watch:")
    terminal.out("  - Client requests/s (dashboard 01, row 1): how high does a closed loop push the rate?")
    terminal.out("  - api in-flight and the Little's Law panel (dashboard 01, row 3): how many requests are inside api at once?")
    terminal.out("  - DB pool in use and waiting (dashboard 02, row 1): is a resource full?")
    terminal.out("  - p99 at the client and at api. Errors.")
    terminal.out()
    terminal.out(Exercises.LINKS)
    terminal.out()
    terminal.out("Commands: make status (time left) · make answer or make bench (the report, after the bench) · make clear (stop the bench)")
    return 0
  }

  override fun answer(state: LabState): Int {
    val report = when (val outcome = engine.report(record = true, key = state.active?.key)) {
      is Done -> outcome.value
      is Refused ->
        return when (outcome.refusal) {
          is Refusal.StillRunning -> report(state)
          Refusal.PrometheusDown -> {
            terminal.out("Prometheus did not answer, or has no data for these windows. Check `make ps`, then run `make answer` again.")
            1
          }
          Refusal.ExerciseChanged -> {
            terminal.out(Exercises.CHANGED)
            1
          }
          else -> 1
        }
    }
    Format.banner(terminal, "CLOSED-LOOP BENCH #${report.n}: ${report.clients} clients for ${report.seconds} s (not scored)")
    table(report.clients, report.before, report.during)
    terminal.out()
    terminal.out("HOW TO READ IT")
    report.notes.forEach { terminal.out("  - $it") }
    terminal.out()
    terminal.out("Read again in ${GuideIndex.where(DOC)}")
    terminal.out()
    terminal.out("The bench is closed and the open load runs again. Use `make bench` for another one, or `make ramp` to see the open load reach the same rate.")
    return 0
  }

  override fun reveal(state: LabState): Int = answer(state)

  override fun hint(state: LabState): Int {
    terminal.out("The bench has no hints and no score. Wait for the end, then `make answer` prints the report.")
    return 0
  }

  override fun status(state: LabState) {
    val active = state.active ?: return
    val params = params(active)
    if (BenchCatalog.running(params)) {
      val left = ceil(params.int("seconds") - params.elapsedSeconds).toInt()
      terminal.out("Closed-loop bench #${active.n}: ${params.int("clients")} clients, $left s left.")
    } else {
      terminal.out("Closed-loop bench #${active.n} is over and the open load is back. Use `make answer` for the report.")
    }
  }

  private fun report(state: LabState): Int {
    status(state)
    return 1
  }

  private fun table(clients: Int, before: WindowReading, bench: WindowReading) {
    terminal.out()
    terminal.out("${"".padEnd(LABEL_COLUMN)}${"before".padStart(VALUE_COLUMN)}${"bench".padStart(VALUE_COLUMN)}")
    terminal.out("${"".padEnd(LABEL_COLUMN)}${"open, 2 min".padStart(VALUE_COLUMN)}${"$clients clients".padStart(VALUE_COLUMN)}")
    row("client  rate", rps(before.client.rate), rps(bench.client.rate))
    row("client  p50", millis(before.client.p50Seconds), millis(bench.client.p50Seconds))
    row("client  p99", millis(before.client.p99Seconds), millis(bench.client.p99Seconds))
    row("client  errors", percent(before.client.errorShare), percent(bench.client.errorShare))
    row("client  mean time", millis(before.clientMeanSeconds), millis(bench.clientMeanSeconds))
    row("api     rate", rps(before.api.rate), rps(bench.api.rate))
    row("api     p50", millis(before.api.p50Seconds), millis(bench.api.p50Seconds))
    row("api     p99", millis(before.api.p99Seconds), millis(bench.api.p99Seconds))
    row("api     errors (5xx)", percent(before.api.errorShare), percent(bench.api.errorShare))
    row("api     in flight", decimal(before.apiInflight), decimal(bench.apiInflight))
  }

  private fun row(label: String, before: String, bench: String) {
    terminal.out("  ${label.padEnd(LABEL_COLUMN - 2)}${before.padStart(VALUE_COLUMN)}${bench.padStart(VALUE_COLUMN)}")
  }

  private fun params(active: ActiveExercise): Params = Params(active.params, Format.hhmm(active.started, zone), now() - active.started)

  private fun decimal(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

  companion object {
    const val MODE = BenchEngine.MODE
    const val DOC = BenchEngine.DOC
    private const val LABEL_COLUMN = 24
    private const val VALUE_COLUMN = 14
    private const val MILLIS_PER_SECOND = 1_000.0
  }
}

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
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

data class BenchOptions(
  val clients: Int = BenchCatalog.DEFAULT_CLIENTS,
  val seconds: Int = BenchCatalog.DEFAULT_SECONDS
)

/**
 * `make bench`: a closed-loop load test on the live lab, not scored. Afterwards it prints the
 * minutes before against the bench window, at the client and at api, and says why the two differ.
 */
class BenchExercise(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val terminal: Terminal,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault()
) : ExerciseMode {

  override val mode: String = MODE

  /** Starts a bench, or prints the report of the one that is over. */
  fun start(options: BenchOptions): Int {
    val state = store.load()
    val active = state.active
    if (active != null && active.mode == MODE) {
      return if (BenchCatalog.running(params(active))) report(state) else answer(state)
    }
    if (active != null) {
      terminal.out("An exercise is still open. Use `make answer`, `make reveal` or `make clear` first.")
      return 1
    }
    val startedAt = now()
    val number = state.history.size + 1
    val params = BenchCatalog.params(options.clients, options.seconds)
    store.save(state.copy(active = ActiveExercise(id = BenchCatalog.ID, mode = MODE, params = params, started = startedAt, n = number)))
    annotator.annotate("bench: ${options.clients} closed-loop clients", listOf(TAG), startedAt)
    annotator.annotate("bench over: open load again", listOf(TAG), startedAt + options.seconds)
    Format.banner(terminal, "CLOSED-LOOP BENCH #$number  ·  started at ${Format.hhmm(startedAt, zone)}")
    terminal.out(
      "For ${options.seconds} s, loadgen stops its open load and runs ${options.clients} closed-loop clients instead. " +
        "Each client sends its next request as soon as the last one answered, with no think time. Same mix of products and checkouts."
    )
    terminal.out("After that, the open load comes back by itself.")
    terminal.out()
    terminal.out("What to watch:")
    terminal.out("  - Client requests/s (dashboard 01, row 1): how high does a closed loop push the rate?")
    terminal.out("  - api in-flight and the Little's Law panel (row 3): how many requests are inside api at once?")
    terminal.out("  - DB pool in use and waiting (dashboard 02, row 1): is a resource full?")
    terminal.out("  - p99 at the client and at api. Errors.")
    terminal.out()
    terminal.out(Exercises.LINKS)
    terminal.out()
    terminal.out("Commands: make status (time left) · make answer or make bench (the report, after the bench) · make clear (stop the bench)")
    return 0
  }

  override fun answer(state: LabState): Int {
    val active = state.active ?: return 1
    val params = params(active)
    if (BenchCatalog.running(params)) {
      return report(state)
    }
    val seconds = params.int("seconds")
    val before = BenchMeasure(probe).read(active.started, BenchCatalog.BEFORE_SECONDS)
    val bench = BenchMeasure(probe).read(active.started + seconds, seconds - BenchCatalog.SETTLE_SECONDS)
    if (before.empty && bench.empty) {
      terminal.out("Prometheus did not answer, or has no data for these windows. Check `make ps`, then run `make answer` again.")
      return 1
    }
    val clients = params.int("clients")
    Format.banner(terminal, "CLOSED-LOOP BENCH #${active.n}: $clients clients for $seconds s (not scored)")
    table(clients, before, bench)
    explain(clients, before, bench)
    store.save(state.copy(active = null, history = state.history + entry(active, params)))
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

  private fun explain(clients: Int, before: WindowReading, bench: WindowReading) {
    val rate = bench.client.rate
    val mean = bench.clientMeanSeconds
    terminal.out()
    terminal.out("HOW TO READ IT")
    if (rate != null && rate > 0) {
      val predicted = clients / rate
      terminal.out(
        "  - A closed client waits for its answer, so at most $clients requests are out at once. Little's Law with no think time: " +
          "$clients = ${rps(rate)} x R, so R = $clients / ${rate.roundToInt()} = ${millis(predicted)}. The measured mean was ${millis(mean)}."
      )
    }
    terminal.out(
      "  - The rate went from ${rps(before.client.rate)} to ${rps(rate)}. A closed loop climbs to the rate the system can serve and stays there. " +
        "It can never send more than that, so it never sees the overload."
    )
    terminal.out(
      "  - Coordinated omission: when the system stalls, each closed client stops and waits. The requests it would have sent during the stall " +
        "are never sent, so they are never measured. Real users do not wait for each other. They keep arriving, and each one meets the stall."
    )
    terminal.out(
      "  - So the bench latency has a cap: the mean is $clients / rate, and no request waits behind more than ${clients - 1} others. " +
        "Open arrivals at the same rate put no cap on the queue, so their p99 keeps growing."
    )
    terminal.out(
      "  - This is why the lab's loadgen is open. It draws Poisson arrivals per tenant and keeps the same rate when the system slows down, " +
        "so the queue and its latency show on the dashboards the way users would feel them."
    )
    terminal.out()
    terminal.out("Read again in ${GuideIndex.where(DOC)}")
  }

  private fun entry(active: ActiveExercise, params: Params): HistoryEntry =
    HistoryEntry(
      n = active.n,
      id = active.id,
      mode = MODE,
      title = BenchCatalog.title(params),
      level = 0,
      score = null,
      hints = 0,
      minutes = ((now() - active.started) / SECONDS_PER_MINUTE * TENTHS).roundToInt() / TENTHS,
      at = now()
    )

  private fun params(active: ActiveExercise): Params = Params(active.params, Format.hhmm(active.started, zone), now() - active.started)

  private fun decimal(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

  companion object {
    const val MODE = Exercises.BENCH
    const val DOC = "Closed-loop clients in this lab > What make bench does; Closed-loop clients in this lab > Why the two p99 values differ"
    private const val TAG = "bench"
    private const val LABEL_COLUMN = 24
    private const val VALUE_COLUMN = 14
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val TENTHS = 10.0
  }
}

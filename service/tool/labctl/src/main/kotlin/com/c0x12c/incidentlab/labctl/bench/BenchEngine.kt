package com.c0x12c.incidentlab.labctl.bench

import com.c0x12c.incidentlab.labctl.exercise.SettleGate
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.control.ScheduledAnnotations
import com.c0x12c.incidentlab.labctl.exercise.ActiveView
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.ExerciseView
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.exercise.Format.millis
import com.c0x12c.incidentlab.labctl.exercise.Format.rps
import com.c0x12c.incidentlab.labctl.exercise.ModeEngine
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.guide.GuidePointer
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.incidentlab.labctl.state.matches
import com.c0x12c.incidentlab.labctl.state.queueFutureMarks
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The 2 minutes before the bench against the bench window, at the client and at api. */
data class BenchReport(
  val n: Int,
  val clients: Int,
  val seconds: Int,
  val before: WindowReading,
  val during: WindowReading,
  @get:JsonIgnore
  val notes: List<String>,
  val guide: List<GuidePointer>
) {
  val explanation: String get() = notes.joinToString("\n\n")
}

/** `make bench` without the terminal: start the closed-loop clients, then report once they are done. */
class BenchEngine(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val gate: SettleGate? = null
) : ModeEngine {

  override val mode: String = MODE

  fun start(clients: Int, seconds: Int, settle: Boolean = true): Outcome<ExerciseView> {
    if (gate != null && settle) {
      val state = store.load()
      if (state.active == null) gate.refusal(state)?.let { return Refused(it) }
    }
    val startedAt = now()
    val active = store.withLock { state, write ->
      if (state.active != null) return@withLock null
      ActiveExercise(
        id = BenchCatalog.ID,
        mode = MODE,
        params = BenchCatalog.params(clients, seconds),
        started = startedAt,
        n = state.history.size + 1,
        key = ActiveExercise.newKey(),
        baselineFrom = ExerciseEngine.baselineFrom(startedAt, state.lastEnded)
      ).also { write(state.copy(active = it)) }
    } ?: return Refused(Refusal.ExerciseOpen)
    val marks = listOf("bench: $clients closed-loop clients" to startedAt, "bench over: open load again" to startedAt + seconds)
    ScheduledAnnotations.write(store, annotator, active.key, listOf(TAG), marks, now)
    return Done(view(active))
  }

  override fun view(active: ActiveExercise): ExerciseView {
    val params = params(active)
    val seconds = params.int("seconds")
    val view = ActiveView(
      n = active.n,
      key = active.key,
      mode = MODE,
      level = null,
      ticket = intro(params.int("clients"), seconds).joinToString("\n"),
      startedAt = active.started,
      elapsedSeconds = params.elapsedSeconds,
      hintsUsed = 0,
      hintsTotal = 0,
      hintPenalty = ExerciseEngine.HINT_PENALTY,
      hints = emptyList(),
      answered = active.answered,
      rules = null,
      baselineFrom = active.baselineFrom ?: (active.started - ExerciseEngine.BASELINE_SECONDS)
    )
    return ExerciseView(view, endsAt = active.started + seconds)
  }

  /**
   * The report once the bench is over. With [record] it also closes the bench and keeps a history
   * entry with no score, like `make answer`; without it, nothing changes. With a [key], it is
   * refused when the open exercise is another one.
   */
  fun report(record: Boolean, key: String? = null): Outcome<BenchReport> {
    val active = store.load().active
    if (!active.matches(key)) return Refused(Refusal.ExerciseChanged)
    if (active == null) return Refused(Refusal.NoExercise)
    if (active.mode != MODE) return Refused(Refusal.WrongMode(active.mode, active.n))
    val params = params(active)
    val seconds = params.int("seconds")
    if (BenchCatalog.running(params)) return Refused(Refusal.StillRunning(ceil(seconds - params.elapsedSeconds).toInt()))
    val before = BenchMeasure(probe).read(active.started, BenchCatalog.BEFORE_SECONDS)
    val bench = BenchMeasure(probe).read(active.started + seconds, seconds - BenchCatalog.SETTLE_SECONDS)
    if (before.empty && bench.empty) return Refused(Refusal.PrometheusDown)
    if (record) {
      val at = now()
      val closed = store.withLock { state, write ->
        (state.active?.key == active.key).also { if (it) write(state.queueFutureMarks(state.active, at).copy(active = null, history = state.history + entry(active, params), lastEnded = at)) }
      }
      if (!closed) return Refused(Refusal.ExerciseChanged)
    }
    val clients = params.int("clients")
    return Done(BenchReport(active.n, clients, seconds, before, bench, notes(clients, before, bench), GuideIndex.pointers(DOC)))
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
      minutes = ExerciseEngine.minutesSince(active.started, now()),
      at = now()
    )

  private fun params(active: ActiveExercise): Params = Params(active.params, Format.hhmm(active.started, zone), now() - active.started)

  companion object {
    const val MODE = ExerciseEngine.BENCH
    const val DOC = "Closed-loop clients in this lab > What make bench does; Closed-loop clients in this lab > Why the two p99 values differ"
    private const val TAG = "bench"
    private const val MILLIS_PER_SECOND = 1_000.0

    /** What the bench does, as printed when it starts. */
    fun intro(clients: Int, seconds: Int): List<String> =
      listOf(
        "For $seconds s, loadgen stops its open load and runs $clients closed-loop clients instead. " +
          "Each client sends its next request as soon as the last one answered, with no think time. Same mix of products and checkouts.",
        "After that, the open load comes back by itself."
      )

    /** Why the two windows differ, with the bench's own numbers. */
    fun notes(clients: Int, before: WindowReading, bench: WindowReading): List<String> {
      val rate = bench.client.rate
      val mean = bench.clientMeanSeconds
      val littlesLaw = rate?.takeIf { it > 0 }?.let {
        "A closed client waits for its answer, so at most $clients requests are out at once. Little's Law with no think time: " +
          "$clients = ${rps(it)} x R, so R = $clients / ${it.roundToInt()} = ${millis(clients / it)}. The measured mean was ${millis(mean)}."
      }
      return listOfNotNull(
        littlesLaw,
        "The rate went from ${rps(before.client.rate)} to ${rps(rate)}. A closed loop climbs to the rate the system can serve and stays there. " +
          "It can never send more than that, so it never sees the overload.",
        "Coordinated omission: when the system stalls, each closed client stops and waits. The requests it would have sent during the stall " +
          "are never sent, so they are never measured. Real users do not wait for each other. They keep arriving, and each one meets the stall.",
        "So the bench latency has a cap: the mean is $clients / rate, and no request waits behind more than ${clients - 1} others. " +
          "Open arrivals at the same rate put no cap on the queue, so their p99 keeps growing.",
        "This is why the lab's loadgen is open. It draws Poisson arrivals per tenant and keeps the same rate when the system slows down, " +
          "so the queue and its latency show on the dashboards the way users would feel them."
      )
    }
  }
}

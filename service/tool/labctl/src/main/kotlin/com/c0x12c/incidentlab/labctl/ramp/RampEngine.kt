package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.exercise.SettleGate
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.control.ScheduledAnnotations
import com.c0x12c.incidentlab.labctl.exercise.ActiveView
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.ExerciseView
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.exercise.ModeEngine
import com.c0x12c.incidentlab.labctl.exercise.Option
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.exercise.PlanView
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.exercise.ScoredResult
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.guide.GuidePointer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.RampSubmission
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.incidentlab.labctl.state.matches
import com.c0x12c.incidentlab.labctl.state.queueFutureMarks
import com.c0x12c.incidentlab.labctl.state.withNote
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/** One finished step. `p99` is in seconds; a null is a window Prometheus had no sample for. */
data class RampRow(
  val step: Int,
  val targetRps: Int,
  val rpsOffered: Double?,
  val rpsAchieved: Double?,
  val p99: Double?,
  val errorShare: Double?,
  val knee: Boolean
)

/** What Prometheus recorded, and the hidden limit. */
data class RampMeasured(
  /** Null when no step bent. */
  val kneeRps: Int?,
  val kneeStep: Int?,
  val maxRps: Double?,
  val resource: String,
  val resourceLabel: String
)

data class RampVerdicts(
  val knee: Boolean,
  val max: Boolean,
  val resource: Boolean
)

data class RampResult(
  val n: Int,
  val title: String,
  /** Null for a reveal, which is not scored. */
  val score: Double?,
  val max: Double,
  val stepsRun: Int,
  val stepsTotal: Int,
  val table: List<RampRow>,
  val measured: RampMeasured,
  /** What the learner said, null for a reveal. */
  val given: RampAnswer?,
  val verdicts: RampVerdicts?,
  val missed: List<String>,
  /** True when Prometheus did not answer, so the table is empty. */
  val prometheusDown: Boolean,
  @get:JsonIgnore
  val notes: List<String>,
  val guide: List<GuidePointer>
) : ScoredResult {
  val explanation: String get() = notes.joinToString("\n\n")
}

data class RampRevealed(
  val wasAnswered: Boolean,
  val result: RampResult
)

/** The steps that ran, read once from Prometheus, ready to score. */
class RampSteps(
  val n: Int,
  val key: String,
  val params: Params,
  val stepsRun: Int,
  val readings: List<StepReading>
)

/** `make ramp` without the terminal: start, read the steps, score an answer, reveal. */
class RampEngine(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val gate: SettleGate? = null
) : ModeEngine {

  override val mode: String = MODE

  fun start(every: Int, settle: Boolean = true): Outcome<ExerciseView> {
    check()?.let { return Refused(it) }
    if (gate != null && settle) gate.refusal(store.load())?.let { return Refused(it) }
    val startedAt = now()
    val mark = gate?.mark(store.load(), startedAt)
    val opened = store.withLock<Outcome<ActiveExercise>> { state, write ->
      val multiplier = ExerciseEngine.loadMultiplier(state)
      when {
        state.active != null -> Refused(Refusal.ExerciseOpen)
        multiplier != 1.0 -> Refused(Refusal.LoadNotOne(multiplier))
        else -> {
          val active = ActiveExercise(
            id = RampCatalog.ID,
            mode = MODE,
            params = RampCatalog.params(random, every),
            started = startedAt,
            n = state.history.size + 1,
            key = ActiveExercise.newKey(),
            baselineFrom = ExerciseEngine.baselineFrom(startedAt, mark?.cleanFrom ?: state.lastEnded)
          )
          write(state.copy(active = active, normal = mark?.normal ?: state.normal))
          Done(active)
        }
      }
    }
    val active = when (opened) {
      is Done -> opened.value
      is Refused -> return opened
    }
    val marks = RampPlan.MULTIPLIERS.indices.map { index -> "ramp step ${index + 1}: ${RampPlan.rps(index)} rps" to startedAt + index * every } +
      ("ramp over: back to $BASE_RPS rps" to startedAt + RampPlan.totalSeconds(every))
    ScheduledAnnotations.write(store, annotator, active.key, listOf(TAG), marks, now)
    return Done(view(active))
  }

  /** What a start would be refused for before any wait: an open exercise, or a load that is not 1. */
  fun check(): Refusal? {
    val state = store.load()
    val multiplier = ExerciseEngine.loadMultiplier(state)
    return when {
      state.active != null -> Refusal.ExerciseOpen
      multiplier != 1.0 -> Refusal.LoadNotOne(multiplier)
      else -> null
    }
  }

  override fun view(active: ActiveExercise): ExerciseView {
    val every = params(active).int("every")
    val view = ActiveView(
      n = active.n,
      key = active.key,
      mode = MODE,
      level = null,
      ticket = intro(every).joinToString("\n"),
      startedAt = active.started,
      elapsedSeconds = now() - active.started,
      hintsUsed = 0,
      hintsTotal = 0,
      hintPenalty = ExerciseEngine.HINT_PENALTY,
      hints = emptyList(),
      answered = active.answered,
      rules = null,
      baselineFrom = active.baselineFrom ?: (active.started - ExerciseEngine.BASELINE_SECONDS)
    )
    return ExerciseView(view, plan = PlanView(RampPlan.rates, every, RampPlan.totalSeconds(every)))
  }

  /** The scored answer again, from the same steps. Null when Prometheus does not answer or the ramp was only revealed. */
  override fun result(active: ActiveExercise): ScoredResult? {
    val given = active.rampAnswer ?: return null
    val params = params(active)
    val readings = RampMeasure(probe).read(active.started, params.int("every"), given.stepsRun) ?: return null
    val answer = RampAnswer(given.kneeRps, given.maxRps, given.resource)
    return result(active.n, params, given.stepsRun, readings, answer, RampMath.verdict(answer, readings, RampCatalog.resource(params)))
  }

  /** Every step, read from Prometheus, once the last one is over. The knee and the limit only exist over the whole range, so an early "none" cannot score. */
  fun steps(key: String? = null): Outcome<RampSteps> {
    val active = store.load().active
    if (!active.matches(key)) return Refused(Refusal.ExerciseChanged)
    if (active == null) return Refused(Refusal.NoExercise)
    if (active.mode != MODE) return Refused(Refusal.WrongMode(active.mode, active.n))
    if (active.answered) return Refused(Refusal.AlreadyAnswered)
    val params = params(active)
    val every = params.int("every")
    val stepsRun = RampPlan.stepsRun(params.elapsedSeconds, every)
    if (stepsRun < RampPlan.size) return Refused(Refusal.TooEarly(ceil(RampPlan.totalSeconds(every) - params.elapsedSeconds).toInt()))
    val readings = RampMeasure(probe).read(active.started, every, stepsRun) ?: return Refused(Refusal.PrometheusDown)
    return Done(RampSteps(active.n, active.key, params, stepsRun, readings))
  }

  /**
   * Scores an answer against steps read by [steps] and keeps the history entry. Refused when another
   * exercise is open by the time it is written. The ramp keeps running until it is cleared.
   */
  fun submit(steps: RampSteps, answer: RampAnswer, note: String = ""): Outcome<RampResult> {
    if (RampCatalog.RESOURCES.none { it.first == answer.resource }) return Refused(Refusal.UnknownKey("resource", answer.resource))
    return store.withLock { state, write ->
      val active = state.active
      when {
        active == null || active.key != steps.key -> Refused(Refusal.ExerciseChanged)
        active.mode != MODE -> Refused(Refusal.WrongMode(active.mode, active.n))
        active.answered -> Refused(Refusal.AlreadyAnswered)
        else -> {
          val verdict = RampMath.verdict(answer, steps.readings, RampCatalog.resource(steps.params))
          val submitted = RampSubmission(answer.kneeRps, answer.maxRps, answer.resource, steps.stepsRun)
          write(state.copy(active = active.copy(answered = true, rampAnswer = submitted), history = state.history + entry(active, steps.params, verdict.points, verdict.missed, note)))
          Done(result(active.n, steps.params, steps.stepsRun, steps.readings, answer, verdict))
        }
      }
    }
  }

  fun answer(answer: RampAnswer, note: String = "", key: String? = null): Outcome<RampResult> =
    when (val steps = steps(key)) {
      is Done -> submit(steps.value, answer, note)
      is Refused -> steps
    }

  fun note(n: Int, note: String) {
    if (note.isNotEmpty()) store.update { it.withNote(n, note) }
  }

  /** The step table and the hidden limit without a score. The first reveal adds a history entry with no score. */
  fun reveal(key: String? = null): Outcome<RampRevealed> {
    val active = store.load().active
    if (!active.matches(key)) return Refused(Refusal.ExerciseChanged)
    if (active == null) return Refused(Refusal.NoExercise)
    if (active.mode != MODE) return Refused(Refusal.WrongMode(active.mode, active.n))
    val params = params(active)
    val every = params.int("every")
    val stepsRun = RampPlan.stepsRun(params.elapsedSeconds, every)
    val readings = RampMeasure(probe).read(active.started, every, stepsRun)
    store.withLock { state, write ->
      val current = state.active
      if (current != null && current.key == active.key && !current.answered) {
        write(state.copy(active = current.copy(answered = true), history = state.history + entry(current, params, score = null)))
      }
    }
    return Done(RampRevealed(active.answered, result(active.n, params, stepsRun, readings.orEmpty(), null, null, prometheusDown = readings == null)))
  }

  /** Ends the ramp named [key] with its own annotation, and only that one. True when it was open. Its future marks are queued like [ExerciseEngine.clear] does. */
  fun clearIf(key: String): Boolean {
    val at = now()
    store.withLock { state, write ->
      state.active?.takeIf { it.key == key }?.also { write(state.queueFutureMarks(it, at).copy(active = null, lastEnded = at)) }
    } ?: return false
    annotator.annotate("lab: ramp cleared", listOf("clear"), at)
    return true
  }

  private fun result(
    n: Int,
    params: Params,
    stepsRun: Int,
    readings: List<StepReading>,
    answer: RampAnswer?,
    verdict: RampVerdict?,
    prometheusDown: Boolean = false
  ): RampResult {
    val knee = RampMath.knee(readings)
    val hidden = RampCatalog.resource(params)
    return RampResult(
      n = n,
      title = RampCatalog.title(params),
      score = verdict?.points,
      max = RampMath.TOTAL_POINTS,
      stepsRun = stepsRun,
      stepsTotal = RampPlan.size,
      table = readings.map { RampRow(it.index + 1, it.targetRps, it.offered, it.achieved, it.p99Seconds, it.errorShare, it.index == knee) },
      measured = RampMeasured(knee?.let(RampPlan::rps), knee?.plus(1), RampMath.maxThroughput(readings), hidden, label(hidden)),
      given = answer,
      verdicts = verdict?.let { RampVerdicts(it.kneeCorrect, it.maxCorrect, it.resourceCorrect) },
      missed = verdict?.missed.orEmpty(),
      prometheusDown = prometheusDown,
      notes = notes(params),
      guide = GuideIndex.pointers(DOC)
    )
  }

  private fun entry(active: ActiveExercise, params: Params, score: Double?, missed: List<String> = emptyList(), note: String = ""): HistoryEntry =
    HistoryEntry(
      n = active.n,
      id = active.id,
      mode = MODE,
      title = RampCatalog.title(params),
      level = 0,
      score = score,
      hints = 0,
      minutes = ExerciseEngine.minutesSince(active.started, now()),
      at = now(),
      missed = missed,
      note = note
    )

  private fun params(active: ActiveExercise): Params = Params(active.params, Format.hhmm(active.started, zone), now() - active.started)

  companion object {
    const val MODE = ExerciseEngine.RAMP
    const val DOC = "Capacity from a ramp test > Finding the knee; Capacity from a ramp test > The ramp in this lab"
    private const val TAG = "ramp"
    private const val PERCENT = 100
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0

    val RESOURCE_OPTIONS: List<Option> = RampCatalog.RESOURCES.map { (key, label) -> Option(key, label) }

    /** The part of a resource's label before the colon, or the key itself. */
    fun label(key: String): String = RampCatalog.RESOURCES.firstOrNull { it.first == key }?.second?.substringBefore(':') ?: key

    /** What the ramp does, as printed when it starts. */
    fun intro(every: Int): List<String> =
      listOf(
        "The load rises in ${RampPlan.size} steps of $every s: ${RampPlan.rates.joinToString(", ")} rps. Total ${minutes(RampPlan.totalSeconds(every))} min. " +
          "After the last step the load goes back to $BASE_RPS rps and every limit is lifted.",
        "Something may limit the lab before ${RampPlan.rates.last()} rps, or nothing may. It changes every run."
      )

    /** How to read the step table, then the hidden limit worked out. */
    fun notes(params: Params): List<String> =
      listOf(
        "offered is what loadgen sent, achieved is what api answered with a 2xx, p99 is api's, errors are the share users saw.",
        "The knee is the first step where p99 is above ${RampMath.KNEE_P99_FACTOR.roundToInt()} times the first step's, or where errors go above " +
          "${(RampMath.KNEE_ERROR_SHARE * PERCENT).roundToInt()}%. p99 bends before achieved stops following offered, because the queue starts before the resource is full. " +
          "Plan with the rate at the bend, not with the rate where the service falls over.",
        RampCatalog.explain(params)
      )

    fun minutes(seconds: Number): String = Format.plain(seconds.toDouble() / SECONDS_PER_MINUTE)
  }
}

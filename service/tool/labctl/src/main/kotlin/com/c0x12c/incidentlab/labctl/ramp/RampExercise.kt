package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.exercise.ExerciseMode
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.exercise.Format.millis
import com.c0x12c.incidentlab.labctl.exercise.Format.percent
import com.c0x12c.incidentlab.labctl.exercise.Format.plain
import com.c0x12c.incidentlab.labctl.exercise.Format.rps
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Quiz
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

data class RampOptions(
  val every: Int = RampPlan.DEFAULT_STEP_SECONDS
)

/**
 * `make ramp`: the load rises in steps while a hidden setup limits the lab. The learner reads the
 * knee, the highest throughput and the limiting resource off the dashboards. The score comes from
 * what Prometheus recorded for the same steps.
 */
class RampExercise(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val terminal: Terminal,
  private val random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault()
) : ExerciseMode {

  override val mode: String = MODE

  fun start(options: RampOptions): Int {
    val state = store.load()
    if (state.active != null) {
      terminal.out("An exercise is still open. Use `make answer`, `make reveal` or `make clear` first.")
      return 1
    }
    val multiplier = (state.base["loadgen"]?.get("rate_multiplier") as? Number)?.toDouble() ?: 1.0
    if (multiplier != 1.0) {
      terminal.out("Background load is ${plain(multiplier)} x. The ramp steps are set from the default $BASE_RPS rps, so run `make load X=1` first.")
      return 1
    }
    val every = options.every
    val startedAt = now()
    val number = state.history.size + 1
    store.save(state.copy(active = ActiveExercise(id = RampCatalog.ID, mode = MODE, params = RampCatalog.params(random, every), started = startedAt, n = number)))
    RampPlan.MULTIPLIERS.indices.forEach { index -> annotator.annotate("ramp step ${index + 1}: ${RampPlan.rps(index)} rps", listOf(TAG), startedAt + index * every) }
    annotator.annotate("ramp over: back to $BASE_RPS rps", listOf(TAG), startedAt + RampPlan.totalSeconds(every))
    Format.banner(terminal, "CAPACITY RAMP #$number  ·  started at ${hhmm(startedAt)}")
    terminal.out(
      "The load rises in ${RampPlan.size} steps of $every s: ${RampPlan.rates.joinToString(", ")} rps. Total ${minutes(RampPlan.totalSeconds(every))} min. " +
        "After the last step the load goes back to $BASE_RPS rps and every limit is lifted."
    )
    terminal.out("Something may limit the lab before ${RampPlan.rates.last()} rps, or nothing may. It changes every run.")
    terminal.out()
    terminal.out("What to watch:")
    terminal.out("  - Offered against achieved: client requests/s (dashboard 01, row 1) against api requests/s by route and status (row 3). Where does achieved stop following offered?")
    terminal.out("  - api p99 by route and in-flight (row 3). p99 bends before the throughput flattens. The rate at the bend is the knee.")
    terminal.out("  - Errors users see (row 1): timeouts and 5xx.")
    terminal.out("  - USE for the pool (dashboard 02, row 1) and for the container (row 3): which one fills first?")
    terminal.out("Each step starts at an annotation on the charts.")
    terminal.out()
    terminal.out(Exercises.LINKS)
    terminal.out()
    terminal.out("Commands: make status (the current step) · make answer (after the last step; earlier, it scores the steps that ran) · make clear (stop the ramp)")
    return 0
  }

  override fun answer(state: LabState): Int {
    val active = state.active ?: return 1
    if (active.answered) {
      terminal.out("This ramp was already submitted. Use `make clear`, then `make ramp` for another one.")
      return 0
    }
    val params = params(active)
    val every = params.int("every")
    val stepsRun = RampPlan.stepsRun(params.elapsedSeconds, every)
    if (stepsRun == 0) {
      terminal.out("The first step (${RampPlan.rps(0)} rps) is not over yet: ${ceil(every - params.elapsedSeconds).toInt()} s to go. Answer after at least one step.")
      return 1
    }
    val steps = RampMeasure(probe).read(active.started, every, stepsRun) ?: return prometheusDown()
    terminal.out("Three questions. Ctrl-C quits without scoring. Rates are in rps.")
    terminal.out()
    val knee = askRate("1) At which offered rate did the service saturate (the knee)? Type 0 if it did not within ${RampPlan.rps(stepsRun - 1)} rps. ") ?: return inputClosed()
    val max = askRate("2) What was the highest successful throughput? ") ?: return inputClosed()
    val resource = choose("3) Which resource limited it?", RampCatalog.RESOURCES) ?: return inputClosed()
    val hidden = RampCatalog.resource(params)
    val verdict = RampMath.verdict(RampAnswer(knee, max, resource), steps, hidden)
    Format.banner(terminal, "RESULT: ${plain(verdict.points)}/${plain(RampMath.TOTAL_POINTS)}  ·  capacity ramp, $stepsRun of ${RampPlan.size} steps run")
    terminal.out("  Knee       : you said ${if (knee <= 0) "none" else rps(knee)}, measured ${kneeText(verdict.knee)}: ${word(verdict.kneeCorrect)} (within one step)")
    terminal.out("  Throughput : you said ${rps(max)}, measured ${rps(verdict.maxThroughput)}: ${word(verdict.maxCorrect)} (within ${(RampMath.MAX_TOLERANCE * PERCENT).roundToInt()}%)")
    terminal.out("  Resource   : you said '${label(resource)}', the hidden limit was '${label(hidden)}': ${word(verdict.resourceCorrect)}")
    explain(params, steps)
    val note = terminal.ask("\nOne line for your future self: how did you read the knee? (Enter to skip) ")?.trim().orEmpty()
    finish(state.copy(active = active.copy(answered = true)), params, verdict.points, verdict.missed, note)
    return 0
  }

  override fun reveal(state: LabState): Int {
    val active = state.active ?: return 1
    val params = params(active)
    val every = params.int("every")
    Format.banner(terminal, "CAPACITY RAMP #${active.n}: ${RampCatalog.title(params)} (${if (active.answered) "already scored" else "not scored"})")
    val steps = RampMeasure(probe).read(active.started, every, RampPlan.stepsRun(params.elapsedSeconds, every))
    if (steps == null) {
      terminal.out("(Prometheus did not answer, so there is no step table.)")
    }
    explain(params, steps.orEmpty())
    if (active.answered) {
      offerClear(state)
    } else {
      finish(state.copy(active = active.copy(answered = true)), params, score = null)
    }
    return 0
  }

  override fun hint(state: LabState): Int {
    terminal.out("The ramp has no hints. Watch the panels named at the start, then `make answer`.")
    return 0
  }

  override fun status(state: LabState) {
    val active = state.active ?: return
    val params = params(active)
    val every = params.int("every")
    val step = RampPlan.stepAt(params.elapsedSeconds, every)
    if (step == null) {
      terminal.out("Capacity ramp #${active.n}: every step has run and the load is back to $BASE_RPS rps. Use `make answer`.")
    } else {
      val into = (params.elapsedSeconds - step * every).roundToInt()
      val left = RampPlan.totalSeconds(every) - params.elapsedSeconds
      terminal.out("Capacity ramp #${active.n}: step ${step + 1} of ${RampPlan.size} at ${RampPlan.rps(step)} rps, $into s into the step, ${minutes(left)} min to the end.")
    }
  }

  private fun explain(params: Params, steps: List<StepReading>) {
    if (steps.isNotEmpty()) {
      val knee = RampMath.knee(steps)
      terminal.out()
      terminal.out("STEP TABLE (each window skips the first ${RampPlan.SETTLE_SECONDS} s of its step)")
      terminal.out("  step   offered  achieved        p99   errors")
      for (step in steps) {
        val mark = if (knee == step.index) "  <- knee" else ""
        terminal.out(
          "  ${(step.index + 1).toString().padStart(STEP_COLUMN)}  ${rps(step.offered).padStart(RATE_COLUMN)}  ${rps(step.achieved).padStart(RATE_COLUMN)}  " +
            "${millis(step.p99Seconds).padStart(TIME_COLUMN)}  ${percent(step.errorShare).padStart(SHARE_COLUMN)}$mark"
        )
      }
      terminal.out("  Measured knee: ${kneeText(knee)}. Highest throughput: ${rps(RampMath.maxThroughput(steps))}.")
    }
    terminal.out()
    terminal.out("HOW TO READ IT")
    terminal.out("  offered is what loadgen sent, achieved is what api answered with a 2xx, p99 is api's, errors are the share users saw.")
    terminal.out(
      "  The knee is the first step where p99 is above ${RampMath.KNEE_P99_FACTOR.roundToInt()} times the first step's, or where errors go above " +
        "${(RampMath.KNEE_ERROR_SHARE * PERCENT).roundToInt()}%. p99 bends before achieved stops following offered, because the queue starts before the resource is full. " +
        "Plan with the rate at the bend, not with the rate where the service falls over."
    )
    terminal.out("  " + RampCatalog.explain(params))
    terminal.out()
    terminal.out("Read again in ${GuideIndex.where(DOC)}")
  }

  private fun finish(state: LabState, params: Params, score: Double?, missed: List<String> = emptyList(), note: String = "") {
    val active = state.active ?: return
    val entry = HistoryEntry(
      n = active.n,
      id = active.id,
      mode = MODE,
      title = RampCatalog.title(params),
      level = 0,
      score = score,
      hints = 0,
      minutes = ((now() - active.started) / SECONDS_PER_MINUTE * TENTHS).roundToInt() / TENTHS,
      at = now(),
      missed = missed,
      note = note
    )
    val next = state.copy(history = state.history + entry)
    store.save(next)
    offerClear(next)
  }

  private fun offerClear(state: LabState) {
    val reply = terminal.ask("\nStop the ramp so the lab goes back to normal? [Y/n] ")
    if (reply == null) {
      terminal.out()
    }
    if (reply == null || reply.trim().lowercase() !in setOf("n", "no")) {
      annotator.annotate("lab: ramp cleared", listOf("clear"), now())
      store.save(state.copy(active = null))
      terminal.out("Ramp stopped. The lab recovers in about 10-60 seconds.")
    } else {
      terminal.out("The ramp keeps running. Use `make clear` when you are done.")
    }
  }

  private fun askRate(prompt: String): Double? {
    while (true) {
      val raw = terminal.ask("$prompt(rps) ") ?: return null
      Quiz.parseNumber(raw)?.takeIf { it >= 0 }?.let { return it }
      terminal.out("  Enter a number of rps, for example 150.")
    }
  }

  private fun choose(title: String, options: List<Pair<String, String>>): String? {
    terminal.out()
    terminal.out(title)
    options.forEachIndexed { index, (_, label) -> terminal.out("  ${(index + 1).toString().padStart(2)}. $label") }
    while (true) {
      val raw = terminal.ask("  Pick a number: ")?.trim() ?: return null
      val index = raw.toIntOrNull()
      if (index != null && index in 1..options.size) {
        return options[index - 1].first
      }
      terminal.out("  Enter a number from 1 to ${options.size}.")
    }
  }

  private fun inputClosed(): Int {
    terminal.out()
    terminal.out("No answer was read (the input closed). Nothing was scored. Run `make answer` again in a terminal.")
    return 1
  }

  private fun prometheusDown(): Int {
    terminal.out("Prometheus did not answer, so nothing can be scored. Check `make ps`, then run `make answer` again.")
    return 1
  }

  private fun params(active: ActiveExercise): Params = Params(active.params, hhmm(active.started), now() - active.started)

  private fun label(key: String): String = RampCatalog.RESOURCES.firstOrNull { it.first == key }?.second?.substringBefore(':') ?: key

  private fun kneeText(knee: Int?): String = knee?.let { "${RampPlan.rps(it)} rps (step ${it + 1})" } ?: "none within the range"

  private fun word(correct: Boolean): String = if (correct) "correct" else "wrong"

  private fun minutes(seconds: Number): String = plain(seconds.toDouble() / SECONDS_PER_MINUTE)

  private fun hhmm(epochSeconds: Double): String = Format.hhmm(epochSeconds, zone)

  companion object {
    const val MODE = "ramp"
    const val DOC = "Capacity from a ramp test > Finding the knee; Capacity from a ramp test > The ramp in this lab"
    private const val TAG = "ramp"
    private const val PERCENT = 100
    private const val STEP_COLUMN = 4
    private const val RATE_COLUMN = 8
    private const val TIME_COLUMN = 9
    private const val SHARE_COLUMN = 7
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val TENTHS = 10.0
  }
}

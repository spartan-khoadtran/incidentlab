package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.exercise.SettleWait
import com.c0x12c.incidentlab.labctl.exercise.SettleGate
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
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Quiz
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

data class RampOptions(
  val every: Int = RampPlan.DEFAULT_STEP_SECONDS,
  val settle: Boolean = true
)

/**
 * `make ramp`: the load rises in steps while a hidden setup limits the lab. The learner reads the
 * knee, the highest throughput and the limiting resource off the dashboards. The score comes from
 * what Prometheus recorded for the same steps. Prompts and printing over [RampEngine].
 */
class RampExercise(
  private val store: StateStore,
  probe: LabProbe,
  annotator: Annotator,
  private val terminal: Terminal,
  random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val gate: SettleGate? = null,
  private val sleep: (Long) -> Unit = Thread::sleep
) : ExerciseMode {

  private val engine = RampEngine(store, probe, annotator, random, now, zone, gate)

  override val mode: String = MODE

  fun start(options: RampOptions): Int {
    if (gate != null && options.settle && engine.check() == null) SettleWait(gate, store, terminal, sleep).await()
    val view = when (val outcome = engine.start(options.every, settle = false)) {
      is Done -> outcome.value.active
      is Refused -> {
        val refusal = outcome.refusal
        terminal.out(
          if (refusal is Refusal.LoadNotOne) {
            "Background load is ${plain(refusal.multiplier)} x. The ramp steps are set from the default $BASE_RPS rps, so run `make load X=1` first."
          } else {
            "An exercise is still open. Use `make answer`, `make reveal` or `make clear` first."
          }
        )
        return 1
      }
    }
    Format.banner(terminal, "CAPACITY RAMP #${view.n}  ·  started at ${hhmm(view.startedAt)}")
    RampEngine.intro(options.every).forEach(terminal::out)
    terminal.out()
    terminal.out("What to watch:")
    terminal.out("  - Offered against achieved: client requests/s (dashboard 01, row 1) against api requests/s by route and status (dashboard 01, row 3). Where does achieved stop following offered?")
    terminal.out("  - api p99 by route and in-flight (dashboard 01, row 3). p99 bends before the throughput flattens. The rate at the bend is the knee.")
    terminal.out("  - Errors users see (dashboard 01, row 1): timeouts and 5xx.")
    terminal.out("  - USE for the pool (dashboard 02, row 1) and for the container (dashboard 04, row 1): which one fills first?")
    terminal.out("Each step starts at an annotation on the charts.")
    terminal.out()
    terminal.out(Exercises.LINKS)
    terminal.out()
    terminal.out("Commands: make status (the current step) · make answer (after the last step) · make clear (stop the ramp)")
    return 0
  }

  override fun answer(state: LabState): Int {
    val steps = when (val outcome = engine.steps(state.active?.key)) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal)
    }
    terminal.out("Three questions. Ctrl-C quits without scoring. Rates are in rps.")
    terminal.out()
    val knee = askRate("1) At which offered rate did the service saturate (the knee)? Type 0 if it did not within ${RampPlan.rates.last()} rps. ") ?: return inputClosed()
    val max = askRate("2) What was the highest successful throughput? ") ?: return inputClosed()
    val resource = choose("3) Which resource limited it?", RampCatalog.RESOURCES) ?: return inputClosed()
    val result = when (val outcome = engine.submit(steps, RampAnswer(knee, max, resource))) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal)
    }
    val verdicts = result.verdicts!!
    Format.banner(terminal, "RESULT: ${plain(result.score!!)}/${plain(result.max)}  ·  capacity ramp, ${result.stepsRun} of ${result.stepsTotal} steps run")
    terminal.out("  Knee       : you said ${if (knee <= 0) "none" else rps(knee)}, measured ${kneeText(result.measured)}: ${word(verdicts.knee)} (within one step)")
    terminal.out("  Throughput : you said ${rps(max)}, measured ${rps(result.measured.maxRps)}: ${word(verdicts.max)} (within ${(RampMath.MAX_TOLERANCE * PERCENT).roundToInt()}%)")
    terminal.out("  Resource   : you said '${RampEngine.label(resource)}', the hidden limit was '${result.measured.resourceLabel}': ${word(verdicts.resource)}")
    explain(result)
    val note = terminal.ask("\nOne line for your future self: how did you read the knee? (Enter to skip) ")?.trim().orEmpty()
    engine.note(result.n, note)
    offerClear(steps.key)
    return 0
  }

  override fun reveal(state: LabState): Int {
    val key = state.active?.key ?: return 1
    val revealed = when (val outcome = engine.reveal(key)) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal)
    }
    val result = revealed.result
    Format.banner(terminal, "CAPACITY RAMP #${result.n}: ${result.title} (${if (revealed.wasAnswered) "already scored" else "not scored"})")
    if (result.prometheusDown) {
      terminal.out("(Prometheus did not answer, so there is no step table.)")
    }
    explain(result)
    offerClear(key)
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
      terminal.out("Capacity ramp #${active.n}: step ${step + 1} of ${RampPlan.size} at ${RampPlan.rps(step)} rps, $into s into the step, ${RampEngine.minutes(left)} min to the end.")
    }
  }

  private fun refused(refusal: Refusal): Int =
    when (refusal) {
      Refusal.AlreadyAnswered -> {
        terminal.out("This ramp was already submitted. Use `make clear`, then `make ramp` for another one.")
        0
      }
      is Refusal.TooEarly -> {
        terminal.out("The ramp is not over yet: ${refusal.secondsLeft} s to go. Answer after the last step (${RampPlan.rates.last()} rps).")
        1
      }
      Refusal.PrometheusDown -> {
        terminal.out("Prometheus did not answer, so nothing can be scored. Check `make ps`, then run `make answer` again.")
        1
      }
      Refusal.ExerciseChanged -> {
        terminal.out(Exercises.CHANGED)
        1
      }
      else -> 1
    }

  private fun explain(result: RampResult) {
    if (result.table.isNotEmpty()) {
      terminal.out()
      terminal.out("STEP TABLE (each window skips the first ${RampPlan.SETTLE_SECONDS} s of its step)")
      terminal.out("  step   offered  achieved        p99   errors")
      for (row in result.table) {
        val mark = if (row.knee) "  <- knee" else ""
        terminal.out(
          "  ${row.step.toString().padStart(STEP_COLUMN)}  ${rps(row.rpsOffered).padStart(RATE_COLUMN)}  ${rps(row.rpsAchieved).padStart(RATE_COLUMN)}  " +
            "${millis(row.p99).padStart(TIME_COLUMN)}  ${percent(row.errorShare).padStart(SHARE_COLUMN)}$mark"
        )
      }
      terminal.out("  Measured knee: ${kneeText(result.measured)}. Highest throughput: ${rps(result.measured.maxRps)}.")
    }
    terminal.out()
    terminal.out("HOW TO READ IT")
    result.notes.forEach { terminal.out("  $it") }
    terminal.out()
    terminal.out("Read again in ${GuideIndex.where(DOC)}")
  }

  /** Offers to stop the ramp named [key]. Only that one: an exercise opened meanwhile keeps running. */
  private fun offerClear(key: String) {
    val reply = terminal.ask("\nStop the ramp so the lab goes back to normal? [Y/n] ")
    if (reply == null) {
      terminal.out()
    }
    if (reply == null || reply.trim().lowercase() !in setOf("n", "no")) {
      if (engine.clearIf(key)) {
        terminal.out("Ramp stopped. The next exercise waits until the lab is back to normal.")
      } else {
        terminal.out(Exercises.NOT_CLEARED)
      }
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

  private fun params(active: ActiveExercise): Params = Params(active.params, hhmm(active.started), now() - active.started)

  private fun kneeText(measured: RampMeasured): String = measured.kneeRps?.let { "$it rps (step ${measured.kneeStep})" } ?: "none within the range"

  private fun word(correct: Boolean): String = if (correct) "correct" else "wrong"

  private fun hhmm(epochSeconds: Double): String = Format.hhmm(epochSeconds, zone)

  companion object {
    const val MODE = RampEngine.MODE
    const val DOC = RampEngine.DOC
    private const val PERCENT = 100
    private const val STEP_COLUMN = 4
    private const val RATE_COLUMN = 8
    private const val TIME_COLUMN = 9
    private const val SHARE_COLUMN = 7
    private const val MILLIS_PER_SECOND = 1_000.0
  }
}

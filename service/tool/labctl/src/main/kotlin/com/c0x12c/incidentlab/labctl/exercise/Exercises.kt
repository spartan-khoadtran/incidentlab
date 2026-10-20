package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.Capabilities
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.ramp.RampMath
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Baseline
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.Choices
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.incidentlab.labctl.state.fresh
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/** The learner-facing commands: prompts and printing over [ExerciseEngine]. Each one returns the process exit code. */
class Exercises(
  private val store: StateStore,
  private val probe: LabProbe,
  annotator: Annotator,
  private val terminal: Terminal,
  private val catalog: ScenarioCatalog = ScenarioCatalog,
  random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  /** The other exercise kinds; each owns the commands while its mode is active. */
  private val modes: List<ExerciseMode> = emptyList(),
  capabilities: Capabilities = Capabilities.ALL,
  private val gate: SettleGate? = null,
  private val sleep: (Long) -> Unit = Thread::sleep
) {

  private val engine = ExerciseEngine(store, probe, annotator, catalog, random, now, zone, capabilities = capabilities, gate = gate)

  private fun modeOf(state: LabState): ExerciseMode? = state.active?.let { active -> modes.firstOrNull { it.mode == active.mode } }

  fun newExercise(options: NewOptions): Int {
    val open = store.load().active != null
    var start = options
    if (gate != null && options.settle && (options.force || !open)) {
      engine.check(options)?.let { return refused(it) }
      if (open) engine.clear()
      SettleWait(gate, store, terminal, sleep).await()
      start = options.copy(settle = false, force = false)
    }
    val view = when (val outcome = engine.start(start)) {
      is Done -> outcome.value.active
      is Refused -> return refused(outcome.refusal)
    }
    banner("EXERCISE #${view.n}  ·  level ${view.level}  ·  ticket opened at ${hhmm(view.startedAt)}")
    terminal.out(view.ticket)
    terminal.out()
    terminal.out("The fault is now being applied to the system. Wait 1-2 minutes for the numbers to show, then start investigating.")
    if (view.mode == TOOLBOX) {
      terminal.out(TOOLBOX_RULES)
      terminal.out("Suggested approach: problem statement -> the 60-second checklist -> the suspect's process, cgroup and /metrics -> the command that proves the cause.")
    } else {
      terminal.out("Suggested approach: problem statement -> RED at the outermost layer -> compare two adjacent layers -> USE for the suspected layer.")
      terminal.out()
      terminal.out(LINKS)
    }
    terminal.out()
    terminal.out("Commands: make hint (a hint, costs 0.5 point) · make answer (submit) · make reveal (show the answer, no score) · make clear (cancel)")
    return 0
  }

  fun hint(): Int {
    val state = store.load()
    modeOf(state)?.let { return it.hint(state) }
    val view = when (val outcome = engine.hint()) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal, "This exercise was already submitted. `make reveal` shows the answer again, `make clear` ends it.")
    }
    val used = view.hintsUsed
    terminal.out("Hint $used/${view.hintsTotal} (-${plain(view.hintPenalty)} point, -${plain(view.hintPenalty * used)} in total): ${view.hints.last()}")
    return 0
  }

  fun answer(): Int {
    val state = store.load()
    modeOf(state)?.let { return it.answer(state) }
    val submitted = "This exercise was already submitted. Use `make clear`, then `make new`."
    val view = when (val outcome = engine.answerable()) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal, submitted)
    }
    val key = view.key
    val options = view.options ?: error("an incident always has options")
    terminal.out("Four questions. Type the number of your choice and press Enter. Ctrl-C quits without scoring.")
    terminal.out("The signals and causes on offer all fit what the ticket shows.")
    val nature = choose("1) What is the nature of the problem?", options.natures) ?: return inputClosed()
    val location = choose("2) Where is the cause?", options.locations) ?: return inputClosed()
    val signal = choose("3) Which signal proves it?", options.signals) ?: return inputClosed()
    val cause = choose("4) What is the root cause?", options.causes) ?: return inputClosed()
    val result = when (val outcome = engine.submit(Choices(nature, location, signal, cause), key = key)) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal, submitted)
    }
    banner(
      "RESULT: ${plain(result.score)}/${plain(result.max)}  (correct ${plain(result.raw)}/${plain(result.max)}, ${result.hintsUsed} hint(s) used)  ·  exercise: ${result.reveal.title}"
    )
    terminal.out("  Nature   : ${result.verdicts.nature}")
    terminal.out("  Location : ${result.verdicts.location}")
    terminal.out("  Signal   : ${result.verdicts.signal}")
    terminal.out("  Cause    : ${if (result.verdicts.cause == ExerciseEngine.PARTIAL) "partly correct" else result.verdicts.cause}")
    explain(result.reveal, pick = cause)
    val note = terminal.ask("\nOne line for your future self: what evidence did you use? (Enter to skip) ")?.trim().orEmpty()
    engine.note(result.n, note)
    offerClear(key)
    return 0
  }

  fun reveal(): Int {
    val state = store.load()
    modeOf(state)?.let { return it.reveal(state) }
    val key = state.active?.key
    val revealed = when (val outcome = engine.reveal(key)) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal)
    }
    banner("EXERCISE #${revealed.n}: ${revealed.reveal.title} (${if (revealed.wasAnswered) "already scored" else "not scored"})")
    explain(revealed.reveal, pick = state.active?.choices?.cause)
    key?.let(::offerClear)
    return 0
  }

  fun clear(): Int {
    engine.clear()
    terminal.out("No fault is running any more. The next `make new` waits until the lab is back to normal.")
    return 0
  }

  fun reset(): Int {
    val kept = store.update { it.fresh(now()) }.history.size
    terminal.out("Fresh start: no exercise, load 1. History kept: $kept exercise(s).")
    return 0
  }

  fun status(): Int {
    val state = store.load()
    val mode = modeOf(state)
    if (mode != null) {
      mode.status(state)
    } else if (state.active == null) {
      terminal.out("No exercise is running.")
    } else {
      when (val outcome = engine.active()) {
        is Done -> {
          val view = outcome.value.active
          val minutes = (view.elapsedSeconds / SECONDS_PER_MINUTE).roundToInt()
          terminal.out("Exercise #${view.n} (level ${view.level}) has been running for $minutes min, ${view.hintsUsed} hint(s) used.")
          terminal.out(view.ticket)
          view.rules?.let { terminal.out(it) }
        }
        is Refused -> refused(outcome.refusal)
      }
    }
    terminal.out()
    terminal.out("Services:")
    for ((service, failure) in probe.serviceStatus()) {
      terminal.out("  ${service.padEnd(SERVICE_COLUMN)} ${if (failure == null) "OK" else "not ready ($failure)"}")
    }
    terminal.out()
    terminal.out(LINKS)
    return 0
  }

  fun list(): Int {
    terminal.out("Exercise catalog (the titles hint at the cause; `make new` picks at random and does not say which one it chose):")
    terminal.out()
    for (level in 1..MAX_LEVEL) {
      terminal.out("Level $level")
      catalog.all.filter { it.level == level }.forEach { scenario ->
        val toolbox = if (scenario.cli.isNotEmpty()) " $TOOLBOX_TAG" else ""
        terminal.out("  - ${scenario.id.padEnd(ID_COLUMN)} ${scenario.title.padEnd(TITLE_COLUMN)} [${scenario.topics.joinToString(", ")}]$toolbox")
      }
    }
    terminal.out()
    terminal.out("Topics: ${Answers.TOPICS.joinToString(", ")}")
    terminal.out("Examples: make new LEVEL=2 · make new TOPIC=async · make new ID=pool_hold")
    terminal.out("$TOOLBOX_TAG: also playable CLI only with `make toolbox` (same options), from the shell `make shell` opens.")
    return 0
  }

  fun history(): Int {
    val view = engine.history()
    if (view.entries.isEmpty()) {
      terminal.out("No exercises yet.")
      return 0
    }
    for (entry in view.entries) {
      val score = entry.score?.let { "${plain(it)}/${plain(MAX_POINTS)}" } ?: "-"
      val at = Instant.ofEpochSecond(entry.at.toLong()).atZone(zone).format(DAY_TIME)
      val missed = if (entry.missed.isEmpty()) "" else "  missed: ${entry.missed.joinToString(", ")}"
      val mode = if (entry.mode == INCIDENT) "" else " (${entry.mode})"
      terminal.out("#${entry.n.toString().padEnd(3)} $at  ${score.padEnd(SCORE_COLUMN)} ${plain(entry.minutes).padStart(MINUTES_COLUMN)} min  ${entry.hints} hint(s)  ${entry.title}$mode$missed")
      if (entry.note.isNotEmpty()) {
        terminal.out("     note: ${entry.note}")
      }
    }
    if (view.average == null && view.rampAverage == null) return 0
    terminal.out()
    view.average?.let { average ->
      terminal.out("Average score: ${String.format(Locale.ROOT, "%.2f", average)}/${plain(MAX_POINTS)} over ${view.scoredCount} exercise(s)")
      terminal.out("By topic: " + view.byTopic.joinToString(" · ") { "${it.topic} ${String.format(Locale.ROOT, "%.1f", it.average)} (${it.count})" })
      view.mostMissedCause?.let { terminal.out("Most missed cause: ${it.label} (${it.count} time(s))") }
    }
    view.rampAverage?.let { terminal.out("Ramp average: ${String.format(Locale.ROOT, "%.2f", it)}/${plain(RampMath.TOTAL_POINTS)} over ${view.rampCount} ramp(s)") }
    return 0
  }

  fun load(multiplier: Double): Int {
    val load = when (val outcome = engine.load(multiplier)) {
      is Done -> outcome.value
      is Refused -> return refused(outcome.refusal)
    }
    terminal.out("Background load = ${plain(load.multiplier)} x ${Baseline.BASE_RPS} rps = ${plain(load.rps)} rps (applied within a few seconds).")
    return 0
  }

  /** Prints why the engine did nothing. A refusal that only means "already done" exits 0, like the commands always did. */
  private fun refused(refusal: Refusal, alreadyAnswered: String = ""): Int {
    val (line, code) = when (refusal) {
      Refusal.ExerciseOpen -> "An exercise is still open. Use `make answer`, `make reveal` or `make clear`, or `make new FORCE=1` to drop it." to 1
      Refusal.NoExercise -> "No exercise is running. Use `make new` to start one." to 1
      is Refusal.LabSettling -> "The lab is still coming back from the last exercise. Try again in a minute." to 1
      is Refusal.Stale -> stale(refusal.n) to 1
      is Refusal.WrongMode -> stale(refusal.n) to 1
      is Refusal.UnknownId -> "No exercise '${refusal.id}'. See `make list`." to 1
      is Refusal.NoCli -> "'${refusal.id}' has no CLI proof yet, so it cannot run with `make toolbox`. `make list` tags the ones that can ($TOOLBOX_TAG)." to 1
      Refusal.NoMatch -> "No exercise matches the LEVEL/TOPIC you chose. See `make list`." to 1
      is Refusal.Unsupported -> unsupported(refusal) to 1
      is Refusal.LoadNotOne -> "Background load is ${plain(refusal.multiplier)} x. This exercise is tuned for the default load, so run `make load X=1` first." to 1
      Refusal.AlreadyAnswered -> alreadyAnswered to 0
      Refusal.ExerciseChanged -> CHANGED to 1
      is Refusal.UnknownKey -> "'${refusal.key}' is not a ${refusal.question} this exercise offers. Nothing was saved. Run `make answer` again." to 1
      is Refusal.RampRunning ->
        "A ramp is running. It holds the load at 1 until its last step ends, in ${refusal.secondsLeft} s. Set the load after that, or `make clear` to stop the ramp." to 1
      Refusal.NoHintsLeft -> "No hints left. Use `make answer` to submit or `make reveal` to see the answer." to 0
      else -> error("an incident command cannot be refused with $refusal")
    }
    terminal.out(line)
    return code
  }

  private fun unsupported(refusal: Refusal.Unsupported): String {
    val what = refusal.id?.let { "'$it' needs" } ?: "Every exercise that matches needs"
    return "$what container settings this lab's controller cannot apply (${refusal.missing.joinToString()}). " +
      "The control container needs the docker socket and the /host mounts: see `make logs S=control`."
  }

  private fun stale(n: Int): String = "Exercise #$n was started by an older version of the lab and cannot be resumed. Use `make clear`, then `make new`."

  private fun choose(title: String, options: List<Option>): String? {
    terminal.out()
    terminal.out(title)
    var group: String? = null
    options.forEachIndexed { index, option ->
      option.group?.takeIf { it != group }?.let {
        group = it
        terminal.out("  -- $it")
      }
      terminal.out("  ${(index + 1).toString().padStart(2)}. ${option.label}")
    }
    while (true) {
      val raw = terminal.ask("  Pick a number: ")?.trim() ?: return null
      val index = raw.toIntOrNull()
      if (index != null && index in 1..options.size) {
        return options[index - 1].key
      }
      terminal.out("  Enter a number from 1 to ${options.size}.")
    }
  }

  private fun inputClosed(): Int {
    terminal.out()
    terminal.out("No answer was read (the input closed). Nothing was scored. Run `make answer` again in a terminal.")
    return 1
  }

  private fun explain(reveal: Reveal, pick: String? = null) {
    terminal.out()
    terminal.out("ANSWER")
    terminal.out("  Nature   : ${reveal.answer.nature.joinToString(" / ") { it.label }}")
    terminal.out("  Location : ${reveal.answer.location.joinToString(" / ") { it.label }}")
    terminal.out("  Signal   : ${reveal.answer.signal.joinToString(" / ") { it.label }}")
    terminal.out("  Cause    : ${reveal.answer.cause.label}")
    terminal.out()
    terminal.out("EXPLANATION")
    terminal.out("  " + reveal.explanation)
    terminal.out()
    terminal.out("TRACE (how to get there, step by step)")
    reveal.steps.forEachIndexed { index, step -> terminal.out("  ${index + 1}. $step") }
    terminal.out()
    terminal.out("EVIDENCE TO LOOK FOR (PromQL, paste into Grafana Explore or Prometheus)")
    reveal.evidence.forEach { terminal.out("  - $it") }
    terminal.out()
    if (reveal.cli.isNotEmpty()) {
      terminal.out("COMMANDS THAT PROVE IT (run them in `make shell`)")
      reveal.cli.forEach { terminal.out("  $ $it") }
      terminal.out()
    }
    ruleOuts(reveal.ruleOuts, pick)
    terminal.out("Read again in ${GuideIndex.where(reveal.guide.joinToString("; ") { it.pointer })}")
  }

  private fun ruleOuts(ruleOuts: List<RuleOut>, pick: String?) {
    if (ruleOuts.isEmpty()) return
    terminal.out("RULE OUT THE LOOK-ALIKES (every cause you could pick, and how to check it)")
    var group: String? = null
    for (ruleOut in ruleOuts) {
      ruleOut.cause.group?.takeIf { it != group }?.let {
        group = it
        terminal.out("  -- $it")
      }
      val tags = listOfNotNull(ROOT_CAUSE.takeIf { ruleOut.correct }, ONE_POINT.takeIf { ruleOut.partial }, YOUR_PICK.takeIf { ruleOut.cause.key == pick })
      val mark = if (ruleOut.correct) "=>" else "  "
      terminal.out("  $mark ${ruleOut.cause.label}" + if (tags.isEmpty()) "" else "  [${tags.joinToString(", ")}]")
      terminal.out("       ${ruleOut.check}")
    }
    terminal.out()
  }

  /** Offers to end the exercise named [key]. Only that one: another exercise opened meanwhile keeps running. */
  private fun offerClear(key: String) {
    val reply = terminal.ask("\nClear the fault so the system goes back to normal? [Y/n] ")
    if (reply == null) {
      terminal.out()
    }
    if (reply == null || reply.trim().lowercase() !in setOf("n", "no")) {
      if (engine.clearIf(key)) {
        terminal.out("Fault cleared. `make new` starts the next exercise once the lab is back to normal.")
      } else {
        terminal.out(NOT_CLEARED)
      }
    } else {
      terminal.out("The fault keeps running so you can keep looking. Use `make clear` when you are done.")
    }
  }

  private fun banner(text: String) = Format.banner(terminal, text)

  private fun hhmm(epochSeconds: Double): String = Format.hhmm(epochSeconds, zone)

  private fun plain(value: Double): String = Format.plain(value)

  companion object {
    const val INCIDENT = ExerciseEngine.INCIDENT
    const val TOOLBOX = ExerciseEngine.TOOLBOX
    const val RAMP = ExerciseEngine.RAMP
    const val BENCH = ExerciseEngine.BENCH
    val MODES = ExerciseEngine.MODES
    val NEW_MODES = ExerciseEngine.NEW_MODES
    const val HINT_PENALTY = ExerciseEngine.HINT_PENALTY
    const val NATURE_POINTS = ExerciseEngine.NATURE_POINTS
    const val LOCATION_POINTS = ExerciseEngine.LOCATION_POINTS
    const val SIGNAL_POINTS = ExerciseEngine.SIGNAL_POINTS
    const val CAUSE_POINTS = ExerciseEngine.CAUSE_POINTS
    const val PARTIAL_POINTS = ExerciseEngine.PARTIAL_POINTS
    const val MAX_POINTS = ExerciseEngine.MAX_POINTS
    const val MIN_LOAD = ExerciseEngine.MIN_LOAD
    const val MAX_LOAD = ExerciseEngine.MAX_LOAD
    const val RECENT_WINDOW = ExerciseEngine.RECENT_WINDOW
    const val TOOLBOX_RULES = ExerciseEngine.TOOLBOX_RULES
    private const val MAX_LEVEL = 3
    private const val SERVICE_COLUMN = 8
    private const val ID_COLUMN = 18
    const val TITLE_COLUMN = 48
    private const val SCORE_COLUMN = 6
    private const val MINUTES_COLUMN = 5
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    private const val TOOLBOX_TAG = "[toolbox]"
    private const val ROOT_CAUSE = "root cause"
    private const val ONE_POINT = "1 point"
    private const val YOUR_PICK = "your pick"
    const val CHANGED = "The exercise changed while you answered: it was cleared, or another one started. Nothing was saved. `make status` shows what is open now."
    const val NOT_CLEARED = "That exercise was already closed, so nothing was cleared. `make status` shows what is open now."
    const val LINKS = "  Grafana     http://localhost:3000   (start on '00 · Start here', the Lab menu lists every board)\n" +
      "  Traces      in Grafana: Explore > Tempo (search, service graph), or click an exemplar dot on a latency panel\n" +
      "  Profiles    in Grafana: Explore > Pyroscope (service = api, pricing, worker), or http://localhost:4040\n" +
      "  Prometheus  http://localhost:9090\n" +
      "  Handbook    http://localhost:4000"
  }
}

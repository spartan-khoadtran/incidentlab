package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Baseline
import com.c0x12c.incidentlab.labctl.scenario.ExerciseContext
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

data class NewOptions(
  val level: Int = 0,
  val topic: String = "",
  val id: String = "",
  val force: Boolean = false,
  val mode: String = Exercises.INCIDENT
)

/** The learner-facing commands. Each one returns the process exit code. */
class Exercises(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val terminal: Terminal,
  private val catalog: ScenarioCatalog = ScenarioCatalog,
  private val random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  /** The other exercise kinds; each owns the commands while its mode is active. */
  private val modes: List<ExerciseMode> = emptyList()
) {

  private fun modeOf(state: LabState): ExerciseMode? = state.active?.let { active -> modes.firstOrNull { it.mode == active.mode } }

  fun newExercise(options: NewOptions): Int {
    val state = store.load()
    if (state.active != null && !options.force) {
      terminal.out("An exercise is still open. Use `make answer`, `make reveal` or `make clear`, or `make new FORCE=1` to drop it.")
      return 1
    }
    val scenario = pick(state, options) ?: return 1
    val context = ExerciseContext(cpuCount = probe.cpuCount(), productDetailSeconds = probe.productDetailSeconds(), tenants = probe.tenants())
    val params = scenario.params(random, context)
    val startedAt = now()
    val number = state.history.size + 1
    store.save(state.copy(active = ActiveExercise(id = scenario.id, mode = options.mode, params = params, started = startedAt, n = number)))
    val rendered = Params(params, hhmm(startedAt))
    if (random.nextDouble() < scenario.decoy) {
      annotator.annotate(Answers.DECOYS.random(random), listOf("change"), startedAt + random.nextInt(-DECOY_MAX_LEAD_SECONDS, DECOY_MAX_LAG_SECONDS + 1))
    }
    scenario.marker?.let { annotator.annotate(it(rendered), listOf("change"), startedAt - random.nextInt(0, MARKER_MAX_LEAD_SECONDS + 1)) }
    banner("EXERCISE #$number  ·  level ${scenario.level}  ·  ticket opened at ${hhmm(startedAt)}")
    terminal.out(scenario.ticket(rendered))
    terminal.out()
    terminal.out("The fault is now being applied to the system. Wait 1-2 minutes for the numbers to show, then start investigating.")
    if (options.mode == TOOLBOX) {
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
    val active = requireActive(state) ?: return 1
    val scenario = catalog.byId.getValue(active.id)
    if (active.answered) {
      terminal.out("This exercise was already submitted. `make reveal` shows the answer again, `make clear` ends it.")
      return 0
    }
    if (active.hints >= scenario.hints.size) {
      terminal.out("No hints left. Use `make answer` to submit or `make reveal` to see the answer.")
      return 0
    }
    val used = active.hints + 1
    store.save(state.copy(active = active.copy(hints = used)))
    terminal.out("Hint $used/${scenario.hints.size} (-${plain(HINT_PENALTY)} point, -${plain(HINT_PENALTY * used)} in total): ${scenario.hints[used - 1]}")
    return 0
  }

  fun answer(): Int {
    val state = store.load()
    modeOf(state)?.let { return it.answer(state) }
    val active = requireActive(state) ?: return 1
    if (active.answered) {
      terminal.out("This exercise was already submitted. Use `make clear`, then `make new`.")
      return 0
    }
    val scenario = catalog.byId.getValue(active.id)
    terminal.out("Four questions. Type the number of your choice and press Enter. Ctrl-C quits without scoring.")
    val nature = choose("1) What is the nature of the problem?", Answers.NATURES) ?: return inputClosed()
    val location = choose("2) Where is the cause?", Answers.LOCATIONS) ?: return inputClosed()
    val signal = choose("3) Which signal proves it?", Answers.SIGNALS) ?: return inputClosed()
    val cause = choose("4) What is the root cause?", Answers.CAUSES, Answers.CAUSE_GROUPS) ?: return inputClosed()
    val expected = scenario.answer
    val missed = mutableListOf<String>()
    var points = 0.0
    if (nature in expected.nature) points += NATURE_POINTS else missed += "nature"
    if (location in expected.location) points += LOCATION_POINTS else missed += "location"
    if (signal in expected.signal) points += SIGNAL_POINTS else missed += "signal"
    val causeVerdict = when (cause) {
      expected.cause -> {
        points += CAUSE_POINTS
        "correct"
      }
      in expected.partial -> {
        points += PARTIAL_POINTS
        missed += "cause (partial)"
        "partly correct"
      }
      else -> {
        missed += "cause"
        "wrong"
      }
    }
    val raw = points
    val score = (points - HINT_PENALTY * active.hints).coerceAtLeast(0.0)
    banner("RESULT: ${plain(score)}/${plain(MAX_POINTS)}  (correct ${plain(raw)}/${plain(MAX_POINTS)}, ${active.hints} hint(s) used)  ·  exercise: ${scenario.title}")
    terminal.out("  Nature   : ${verdict("nature" !in missed)}")
    terminal.out("  Location : ${verdict("location" !in missed)}")
    terminal.out("  Signal   : ${verdict("signal" !in missed)}")
    terminal.out("  Cause    : $causeVerdict")
    explain(scenario, active)
    val note = terminal.ask("\nOne line for your future self: what evidence did you use? (Enter to skip) ")?.trim().orEmpty()
    finish(state.copy(active = active.copy(answered = true)), score, missed, note)
    return 0
  }

  private fun verdict(correct: Boolean): String = if (correct) "correct" else "wrong"

  fun reveal(): Int {
    val state = store.load()
    modeOf(state)?.let { return it.reveal(state) }
    val active = requireActive(state) ?: return 1
    val scenario = catalog.byId.getValue(active.id)
    if (active.answered) {
      banner("EXERCISE #${active.n}: ${scenario.title} (already scored)")
      explain(scenario, active)
      offerClear(state)
      return 0
    }
    banner("EXERCISE #${active.n}: ${scenario.title} (not scored)")
    explain(scenario, active)
    finish(state.copy(active = active.copy(answered = true)), score = null)
    return 0
  }

  fun clear(): Int {
    val state = store.load()
    if (state.active != null) {
      annotator.annotate("lab: fault cleared", listOf("clear"), now())
    }
    store.save(state.copy(active = null))
    terminal.out("No fault is running any more. The system recovers in about 10-60 seconds.")
    return 0
  }

  fun status(): Int {
    val state = store.load()
    val active = state.active
    val mode = modeOf(state)
    if (mode != null) {
      mode.status(state)
    } else if (active == null) {
      terminal.out("No exercise is running.")
    } else if (resumable(active)) {
      val scenario = catalog.byId.getValue(active.id)
      val minutes = ((now() - active.started) / SECONDS_PER_MINUTE).roundToInt()
      terminal.out("Exercise #${active.n} (level ${scenario.level}) has been running for $minutes min, ${active.hints} hint(s) used.")
      terminal.out(scenario.ticket(Params(active.params, hhmm(active.started))))
      if (active.mode == TOOLBOX) {
        terminal.out(TOOLBOX_RULES)
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
    val history = store.load().history
    if (history.isEmpty()) {
      terminal.out("No exercises yet.")
      return 0
    }
    val scored = history.filter { it.score != null }
    for (entry in history) {
      val score = entry.score?.let { "${plain(it)}/${plain(MAX_POINTS)}" } ?: "-"
      val at = Instant.ofEpochSecond(entry.at.toLong()).atZone(zone).format(DAY_TIME)
      val missed = if (entry.missed.isEmpty()) "" else "  missed: ${entry.missed.joinToString(", ")}"
      val mode = if (entry.mode == INCIDENT) "" else " (${entry.mode})"
      terminal.out("#${entry.n.toString().padEnd(3)} $at  ${score.padEnd(SCORE_COLUMN)} ${plain(entry.minutes).padStart(MINUTES_COLUMN)} min  ${entry.hints} hint(s)  ${entry.title}$mode$missed")
      if (entry.note.isNotEmpty()) {
        terminal.out("     note: ${entry.note}")
      }
    }
    if (scored.isNotEmpty()) {
      terminal.out()
      terminal.out("Average score: ${String.format(Locale.ROOT, "%.2f", scored.map { it.score!! }.average())}/${plain(MAX_POINTS)} over ${scored.size} exercise(s)")
      terminal.out("By topic: " + byTopic(scored).joinToString(" · ") { (topic, average, count) -> "$topic ${String.format(Locale.ROOT, "%.1f", average)} ($count)" })
      mostMissedCause(scored)?.let { (cause, count) -> terminal.out("Most missed cause: ${Answers.label(Answers.CAUSES, cause)} ($count time(s))") }
    }
    return 0
  }

  private fun byTopic(scored: List<HistoryEntry>): List<Triple<String, Double, Int>> =
    scored.flatMap { entry -> catalog.byId[entry.id]?.topics.orEmpty().map { topic -> topic to entry.score!! } }
      .groupBy({ it.first }, { it.second })
      .map { (topic, scores) -> Triple(topic, scores.average(), scores.size) }
      .sortedBy { it.second }

  private fun mostMissedCause(scored: List<HistoryEntry>): Pair<String, Int>? =
    scored.filter { entry -> entry.missed.any { it.startsWith("cause") } }
      .mapNotNull { catalog.byId[it.id]?.answer?.cause }
      .groupingBy { it }.eachCount()
      .maxByOrNull { it.value }?.toPair()

  fun load(multiplier: Double): Int {
    val x = multiplier.coerceIn(MIN_LOAD, MAX_LOAD)
    store.update { state ->
      val loadgen = state.base["loadgen"].orEmpty() + ("rate_multiplier" to x)
      state.copy(base = state.base + ("loadgen" to loadgen))
    }
    terminal.out("Background load = ${plain(x)} x ${Baseline.BASE_RPS} rps = ${plain(Baseline.BASE_RPS * x)} rps (applied within a few seconds).")
    return 0
  }

  private fun pick(state: LabState, options: NewOptions): Scenario? {
    val toolbox = options.mode == TOOLBOX
    if (options.id.isNotEmpty()) {
      val scenario = catalog.byId[options.id] ?: run {
        terminal.out("No exercise '${options.id}'. See `make list`.")
        return null
      }
      if (toolbox && scenario.cli.isEmpty()) {
        terminal.out("'${options.id}' has no CLI proof yet, so it cannot run with `make toolbox`. `make list` tags the ones that can ($TOOLBOX_TAG).")
        return null
      }
      return scenario
    }
    val pool = catalog.all.filter {
      (options.level == 0 || it.level == options.level) && (options.topic.isEmpty() || options.topic in it.topics) && (!toolbox || it.cli.isNotEmpty())
    }
    if (pool.isEmpty()) {
      terminal.out("No exercise matches the LEVEL/TOPIC you chose. See `make list`.")
      return null
    }
    val recent = state.history.takeLast(RECENT_WINDOW).map { it.id }
    val fresh = pool.filter { it.id !in recent }
    return (fresh.ifEmpty { pool }).random(random)
  }

  private fun requireActive(state: LabState): ActiveExercise? {
    val active = state.active ?: run {
      terminal.out("No exercise is running. Use `make new` to start one.")
      return null
    }
    return active.takeIf { resumable(it) }
  }

  /** False when the exercise was started by an older catalog and its params no longer render. */
  private fun resumable(active: ActiveExercise): Boolean {
    val renders = catalog.byId[active.id]?.let { scenario -> runCatching { scenario.ticket(Params(active.params, hhmm(active.started))) }.isSuccess } ?: false
    if (!renders) {
      terminal.out("Exercise #${active.n} was started by an older version of the lab and cannot be resumed. Use `make clear`, then `make new`.")
    }
    return renders
  }

  private fun choose(title: String, options: List<Pair<String, String>>, groups: Map<String, String> = emptyMap()): String? {
    terminal.out()
    terminal.out(title)
    var group: String? = null
    options.forEachIndexed { index, (key, label) ->
      groups[key]?.takeIf { it != group }?.let {
        group = it
        terminal.out("  -- $it")
      }
      terminal.out("  ${(index + 1).toString().padStart(2)}. $label")
    }
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

  private fun explain(scenario: Scenario, active: ActiveExercise) {
    val params = Params(active.params, hhmm(active.started))
    terminal.out()
    terminal.out("ANSWER")
    terminal.out("  Nature   : ${scenario.answer.nature.joinToString(" / ") { Answers.label(Answers.NATURES, it) }}")
    terminal.out("  Location : ${scenario.answer.location.joinToString(" / ") { Answers.label(Answers.LOCATIONS, it) }}")
    terminal.out("  Signal   : ${scenario.answer.signal.joinToString(" / ") { Answers.label(Answers.SIGNALS, it) }}")
    terminal.out("  Cause    : ${Answers.label(Answers.CAUSES, scenario.answer.cause)}")
    terminal.out()
    terminal.out("EXPLANATION")
    terminal.out("  " + scenario.explain(params))
    terminal.out()
    terminal.out("TRACE (how to get there, step by step)")
    scenario.steps.forEachIndexed { index, step -> terminal.out("  ${index + 1}. $step") }
    terminal.out()
    terminal.out("EVIDENCE TO LOOK FOR (PromQL, paste into Grafana Explore or Prometheus)")
    scenario.evidence.forEach { terminal.out("  - $it") }
    terminal.out()
    if (scenario.cli.isNotEmpty()) {
      terminal.out("COMMANDS THAT PROVE IT (run them in `make shell`)")
      scenario.cli.forEach { terminal.out("  $ $it") }
      terminal.out()
    }
    terminal.out("Read again in ${GuideIndex.where(scenario.doc)}")
  }

  private fun finish(state: LabState, score: Double?, missed: List<String> = emptyList(), note: String = "") {
    val active = state.active ?: return
    val scenario = catalog.byId.getValue(active.id)
    val entry = HistoryEntry(
      n = active.n,
      id = active.id,
      mode = active.mode,
      title = scenario.title,
      level = scenario.level,
      score = score,
      hints = active.hints,
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
    val reply = terminal.ask("\nClear the fault so the system goes back to normal? [Y/n] ")
    if (reply == null) {
      terminal.out()
    }
    val clearIt = reply == null || reply.trim().lowercase() !in setOf("n", "no")
    if (clearIt) {
      annotator.annotate("lab: fault cleared", listOf("clear"), now())
      store.save(state.copy(active = null))
      terminal.out("Fault cleared. The system recovers in about 10-60 seconds. Use `make new` for the next exercise.")
    } else {
      terminal.out("The fault keeps running so you can keep looking. Use `make clear` when you are done.")
    }
  }

  private fun banner(text: String) {
    terminal.out()
    terminal.out("=".repeat(BANNER_WIDTH))
    terminal.out(text)
    terminal.out("=".repeat(BANNER_WIDTH))
  }

  private fun hhmm(epochSeconds: Double): String = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).format(HOUR_MINUTE)

  private fun plain(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

  companion object {
    const val INCIDENT = "incident"
    const val TOOLBOX = "toolbox"
    const val RAMP = "ramp"
    const val BENCH = "bench"
    val MODES = listOf(INCIDENT, TOOLBOX, RAMP, BENCH)

    /** The modes `make new` starts. The ramp and the bench have their own commands. */
    val NEW_MODES = listOf(INCIDENT, TOOLBOX)
    const val HINT_PENALTY = 0.5
    const val NATURE_POINTS = 1.0
    const val LOCATION_POINTS = 1.0
    const val SIGNAL_POINTS = 1.0
    const val CAUSE_POINTS = 2.0
    const val PARTIAL_POINTS = 1.0
    const val MAX_POINTS = NATURE_POINTS + LOCATION_POINTS + SIGNAL_POINTS + CAUSE_POINTS
    const val MIN_LOAD = 0.1
    const val MAX_LOAD = 6.0
    private const val MAX_LEVEL = 3
    private const val RECENT_WINDOW = 5
    private const val DECOY_MAX_LEAD_SECONDS = 600
    private const val DECOY_MAX_LAG_SECONDS = 60
    private const val MARKER_MAX_LEAD_SECONDS = 180
    private const val BANNER_WIDTH = 72
    private const val SERVICE_COLUMN = 8
    private const val ID_COLUMN = 18
    const val TITLE_COLUMN = 48
    private const val SCORE_COLUMN = 6
    private const val MINUTES_COLUMN = 5
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val TENTHS = 10.0
    private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    private const val TOOLBOX_TAG = "[toolbox]"
    const val TOOLBOX_RULES = "CLI only: do not open Grafana. `make shell` gives you a toolbox on the host. Prove the cause with commands."
    const val LINKS = "  Grafana     http://localhost:3000   (dashboards '01 · RED by layer' and '02 · USE by layer')\n" +
      "  Traces      in Grafana: Explore > Tempo (search, service graph), or click an exemplar dot on a latency panel\n" +
      "  Profiles    in Grafana: Explore > Pyroscope (service = api, pricing, worker), or http://localhost:4040\n" +
      "  Prometheus  http://localhost:9090"
  }
}

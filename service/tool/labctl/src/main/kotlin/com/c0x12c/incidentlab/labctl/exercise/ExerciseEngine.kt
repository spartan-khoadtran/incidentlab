package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.Capabilities
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.ramp.RampPlan
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Baseline
import com.c0x12c.incidentlab.labctl.scenario.ExerciseContext
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.scenario.Symptom
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.Choices
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.incidentlab.labctl.state.matches
import com.c0x12c.incidentlab.labctl.state.queueFutureMarks
import com.c0x12c.incidentlab.labctl.state.withNote
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

data class NewOptions(
  val level: Int = 0,
  val topic: String = "",
  val id: String = "",
  val force: Boolean = false,
  val mode: String = ExerciseEngine.INCIDENT
)

/** A mode with its own engine. The incident engine asks it for the view of an exercise in that mode. */
interface ModeEngine {
  val mode: String

  fun view(active: ActiveExercise): ExerciseView

  /** The scored answer of an answered exercise, or null. May read Prometheus. */
  fun result(active: ActiveExercise): ScoredResult? = null
}

data class OptionsView(
  val levels: List<Int>,
  val topics: List<String>,
  val modes: List<String>
)

/**
 * The incident and toolbox exercises: every decision, no printing. The CLI and the HTTP API both
 * call it and word the outcome their own way. Every change is a read-modify-write under the state
 * file's lock; calls to Prometheus and Grafana stay outside it.
 */
class ExerciseEngine(
  private val store: StateStore,
  private val probe: LabProbe,
  private val annotator: Annotator,
  private val catalog: ScenarioCatalog = ScenarioCatalog,
  private val random: Random = Random.Default,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val modes: List<ModeEngine> = emptyList(),
  private val capabilities: Capabilities = Capabilities.ALL,
  private val offer: (Symptom) -> AnswerOptions = { answerOptions(it) }
) {

  private val dockerNeeds = ConcurrentHashMap<String, Set<String>>()

  private data class Found(val active: ActiveExercise, val scenario: Scenario)

  fun start(options: NewOptions): Outcome<ExerciseView> {
    val state = store.load()
    if (state.active != null && !options.force) return Refused(Refusal.ExerciseOpen)
    val scenario = when (val picked = pick(state, options)) {
      is Done -> picked.value
      is Refused -> return picked
    }
    val context = ExerciseContext(cpuCount = probe.cpuCount(), productDetailSeconds = probe.productDetailSeconds(), tenants = probe.tenants())
    val params = scenario.params(random, context)
    val startedAt = now()
    val active = store.withLock { current, write ->
      if (current.active != null && !options.force) return@withLock null
      val opened = ActiveExercise(id = scenario.id, mode = options.mode, params = params, started = startedAt, n = current.history.size + 1, key = ActiveExercise.newKey())
      write(current.queueFutureMarks(current.active, startedAt).copy(active = opened))
      opened
    } ?: return Refused(Refusal.ExerciseOpen)
    if (random.nextDouble() < scenario.decoy) {
      annotator.annotate(Answers.DECOYS.random(random), listOf("change"), startedAt + random.nextInt(-DECOY_MAX_LEAD_SECONDS, DECOY_MAX_LAG_SECONDS + 1))
    }
    scenario.marker?.let { annotator.annotate(it(rendered(active)), listOf("change"), startedAt - random.nextInt(0, MARKER_MAX_LEAD_SECONDS + 1)) }
    return Done(ExerciseView(view(active, scenario)))
  }

  /** The open exercise in any mode. An answered one carries its result, and an incident its reveal. */
  fun active(): Outcome<ExerciseView> = current(withResult = true)

  private fun current(withResult: Boolean): Outcome<ExerciseView> {
    val state = store.load()
    val active = state.active ?: return Refused(Refusal.NoExercise)
    modes.firstOrNull { it.mode == active.mode }?.let { mode ->
      val view = mode.view(active)
      return Done(if (withResult && active.answered) view.copy(result = mode.result(active)) else view)
    }
    return when (val found = incident(state)) {
      is Refused -> found
      is Done -> {
        val (open, scenario) = found.value
        if (open.answered && withResult) {
          Done(ExerciseView(view(open, scenario), reveal = reveal(open, scenario), result = open.choices?.let { score(open, scenario, it) }))
        } else {
          Done(ExerciseView(view(open, scenario)))
        }
      }
    }
  }

  fun hint(key: String? = null): Outcome<ActiveView> =
    store.withLock { state, write ->
      if (!state.active.matches(key)) return@withLock Refused(Refusal.ExerciseChanged)
      when (val found = incident(state)) {
        is Refused -> found
        is Done -> {
          val (active, scenario) = found.value
          when {
            active.answered -> Refused(Refusal.AlreadyAnswered)
            active.hints >= scenario.hints.size -> Refused(Refusal.NoHintsLeft)
            else -> {
              val next = active.copy(hints = active.hints + 1)
              write(state.copy(active = next))
              Done(view(next, scenario))
            }
          }
        }
      }
    }

  /**
   * The open exercise if [submit] would take an answer now. The CLI asks before it prompts with its
   * options, then submits with its key.
   */
  fun answerable(): Outcome<ActiveView> =
    when (val found = incident(store.load())) {
      is Refused -> found
      is Done -> if (found.value.active.answered) Refused(Refusal.AlreadyAnswered) else Done(view(found.value.active, found.value.scenario))
    }

  /**
   * Scores the four picks and keeps the history entry. The fault keeps running until [clear]. With a
   * [key], the answer is refused when another exercise is open by the time it is written.
   */
  fun submit(choices: Choices, note: String = "", key: String? = null): Outcome<Result> =
    store.withLock { state, write ->
      if (!state.active.matches(key)) return@withLock Refused(Refusal.ExerciseChanged)
      when (val found = incident(state)) {
        is Refused -> found
        is Done -> {
          val (active, scenario) = found.value
          if (active.answered) return@withLock Refused(Refusal.AlreadyAnswered)
          unknownKey(choices, options(active, scenario))?.let { return@withLock Refused(it) }
          val result = score(active, scenario, choices)
          val entry = entry(active, scenario.title, scenario.level, result.score, result.missed, note)
          write(state.copy(active = active.copy(answered = true, choices = choices), history = state.history + entry))
          Done(result)
        }
      }
    }

  /** Sets the note of the latest history entry numbered [n]. */
  fun note(n: Int, note: String) {
    if (note.isNotEmpty()) store.update { it.withNote(n, note) }
  }

  /** The answer without a score. The first reveal of an exercise adds a history entry with no score. */
  fun reveal(key: String? = null): Outcome<Revealed> =
    store.withLock { state, write ->
      if (!state.active.matches(key)) return@withLock Refused(Refusal.ExerciseChanged)
      when (val found = incident(state)) {
        is Refused -> found
        is Done -> {
          val (active, scenario) = found.value
          if (!active.answered) {
            val entry = entry(active, scenario.title, scenario.level, score = null)
            write(state.copy(active = active.copy(answered = true), history = state.history + entry))
          }
          Done(Revealed(active.n, active.answered, reveal(active, scenario)))
        }
      }
    }

  /**
   * Ends the open exercise of any mode. True when one was open. With a [key], it is refused when
   * the open exercise is another one. Its marks still in the future are left in the state for the
   * control daemon to delete, so a slow Grafana costs this call one write at most.
   */
  fun clear(key: String? = null): Outcome<Boolean> {
    val at = now()
    val ended = store.withLock<Outcome<ActiveExercise?>> { state, write ->
      if (state.active != null && !state.active.matches(key)) return@withLock Refused(Refusal.ExerciseChanged)
      write(state.queueFutureMarks(state.active, at).copy(active = null))
      Done(state.active)
    }
    return when (ended) {
      is Done -> {
        if (ended.value != null) annotator.annotate(CLEARED, listOf("clear"), at)
        Done(ended.value != null)
      }
      is Refused -> ended
    }
  }

  /** Ends the exercise named [key] only, so a late reply cannot end a newer one. True when it was open. */
  fun clearIf(key: String): Boolean = (clear(key) as? Done)?.value == true

  fun status(): LabStatus {
    val active = (current(withResult = false) as? Done)?.value?.active
    return LabStatus(probe.serviceStatus(), active, loadMultiplier(store.load()), Baseline.BASE_RPS, HOST_LINKS)
  }

  fun history(): HistoryView {
    val history = store.load().history
    val scored = history.filter { it.score != null && it.mode in NEW_MODES }
    val ramps = history.filter { it.score != null && it.mode == RAMP }
    val missed = mostMissedCause(scored)?.let { (cause, count) ->
      MissedCause(cause, Answers.label(Answers.CAUSES, cause), Answers.CAUSE_GROUPS[cause], count)
    }
    return HistoryView(history, average(scored), scored.size, byTopic(scored), missed, average(ramps), ramps.size)
  }

  /** Sets the background load. Refused while a ramp step runs, because the ramp holds the load at 1 until its last step ends. */
  fun load(multiplier: Double): Outcome<LoadView> {
    val x = multiplier.coerceIn(MIN_LOAD, MAX_LOAD)
    return store.withLock { state, write ->
      rampSecondsLeft(state.active)?.let { return@withLock Refused(Refusal.RampRunning(it)) }
      val loadgen = state.base["loadgen"].orEmpty() + ("rate_multiplier" to x)
      write(state.copy(base = state.base + ("loadgen" to loadgen)))
      Done(LoadView(x, Baseline.BASE_RPS * x))
    }
  }

  private fun rampSecondsLeft(active: ActiveExercise?): Int? {
    if (active?.mode != RAMP) return null
    val every = (active.params["every"] as? Number)?.toInt() ?: return null
    val left = active.started + RampPlan.totalSeconds(every) - now()
    return if (left > 0) ceil(left).toInt() else null
  }

  fun options(): OptionsView =
    OptionsView(
      levels = levels(),
      topics = Answers.TOPICS,
      modes = NEW_MODES
    )

  fun levels(): List<Int> = catalog.all.map { it.level }.distinct().sorted()

  private fun average(entries: List<HistoryEntry>): Double? = entries.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()

  private fun byTopic(scored: List<HistoryEntry>): List<TopicAverage> =
    scored.flatMap { entry -> catalog.byId[entry.id]?.topics.orEmpty().map { topic -> topic to entry.score!! } }
      .groupBy({ it.first }, { it.second })
      .map { (topic, scores) -> TopicAverage(topic, scores.average(), scores.size) }
      .sortedBy { it.average }

  private fun mostMissedCause(scored: List<HistoryEntry>): Pair<String, Int>? =
    scored.filter { entry -> entry.missed.any { it.startsWith("cause") } }
      .mapNotNull { catalog.byId[it.id]?.answer?.cause }
      .groupingBy { it }.eachCount()
      .maxByOrNull { it.value }?.toPair()

  private fun pick(state: LabState, options: NewOptions): Outcome<Scenario> {
    val toolbox = options.mode == TOOLBOX
    val supported = capabilities.dockerKeys()
    val load = loadMultiplier(state)
    if (options.id.isNotEmpty()) {
      val scenario = catalog.byId[options.id] ?: return Refused(Refusal.UnknownId(options.id))
      if (toolbox && scenario.cli.isEmpty()) return Refused(Refusal.NoCli(options.id))
      val missing = missing(scenario, supported)
      if (missing.isNotEmpty()) return Refused(Refusal.Unsupported(scenario.id, missing))
      if (scenario.baseLoad && load != 1.0) return Refused(Refusal.LoadNotOne(load))
      return Done(scenario)
    }
    val matching = catalog.all.filter {
      (options.level == 0 || it.level == options.level) && (options.topic.isEmpty() || options.topic in it.topics) && (!toolbox || it.cli.isNotEmpty())
    }
    if (matching.isEmpty()) return Refused(Refusal.NoMatch)
    val runnable = matching.filter { missing(it, supported).isEmpty() }
    if (runnable.isEmpty()) return Refused(Refusal.Unsupported(null, matching.flatMap { missing(it, supported) }.toSortedSet()))
    val pool = runnable.filter { !it.baseLoad || load == 1.0 }
    if (pool.isEmpty()) return Refused(Refusal.LoadNotOne(load))
    val recent = state.history.takeLast(RECENT_WINDOW).map { it.id }
    val fresh = pool.filter { it.id !in recent }
    return Done((fresh.ifEmpty { pool }).random(random))
  }

  private fun missing(scenario: Scenario, supported: Set<String>?): Set<String> =
    if (supported == null) emptySet() else dockerNeeds.computeIfAbsent(scenario.id) { dockerKeys(scenario) } - supported

  /** The open incident or toolbox exercise, if it still renders with this catalog. */
  private fun incident(state: LabState): Outcome<Found> {
    val active = state.active ?: return Refused(Refusal.NoExercise)
    if (active.mode !in NEW_MODES) return Refused(Refusal.WrongMode(active.mode, active.n))
    val scenario = catalog.byId[active.id]?.takeIf { candidate -> runCatching { rendered(active).let { candidate.ticket(it) to candidate.symptom(it) } }.isSuccess }
      ?: return Refused(Refusal.Stale(active.n))
    return Done(Found(active, scenario))
  }

  private fun unknownKey(choices: Choices, offered: AnswerOptions): Refusal? =
    listOf(
      Triple("nature", choices.nature, offered.natures),
      Triple("location", choices.location, offered.locations),
      Triple("signal", choices.signal, offered.signals),
      Triple("cause", choices.cause, offered.causes)
    ).firstOrNull { (_, key, options) -> options.none { it.key == key } }?.let { (question, key, _) -> Refusal.UnknownKey(question, key) }

  private fun score(active: ActiveExercise, scenario: Scenario, choices: Choices): Result {
    val expected = scenario.answer
    val missed = mutableListOf<String>()
    var points = 0.0
    if (choices.nature in expected.nature) points += NATURE_POINTS else missed += "nature"
    if (choices.location in expected.location) points += LOCATION_POINTS else missed += "location"
    if (choices.signal in expected.signal) points += SIGNAL_POINTS else missed += "signal"
    val cause = when (choices.cause) {
      expected.cause -> {
        points += CAUSE_POINTS
        CORRECT
      }
      in expected.partial -> {
        points += PARTIAL_POINTS
        missed += "cause (partial)"
        PARTIAL
      }
      else -> {
        missed += "cause"
        WRONG
      }
    }
    val verdicts = Verdicts(verdict("nature" !in missed), verdict("location" !in missed), verdict("signal" !in missed), cause)
    val score = (points - HINT_PENALTY * active.hints).coerceAtLeast(0.0)
    return Result(active.n, score, points, MAX_POINTS, active.hints, verdicts, missed, reveal(active, scenario))
  }

  private fun verdict(correct: Boolean): String = if (correct) CORRECT else WRONG

  private fun view(active: ActiveExercise, scenario: Scenario): ActiveView =
    ActiveView(
      n = active.n,
      key = active.key,
      mode = active.mode,
      level = scenario.level,
      ticket = scenario.ticket(rendered(active)),
      startedAt = active.started,
      elapsedSeconds = now() - active.started,
      hintsUsed = active.hints,
      hintsTotal = scenario.hints.size,
      hintPenalty = HINT_PENALTY,
      hints = scenario.hints.take(active.hints),
      answered = active.answered,
      rules = if (active.mode == TOOLBOX) TOOLBOX_RULES else null,
      options = options(active, scenario)
    )

  private fun options(active: ActiveExercise, scenario: Scenario): AnswerOptions = offer(scenario.symptom(rendered(active)))

  private fun reveal(active: ActiveExercise, scenario: Scenario): Reveal {
    val answer = scenario.answer
    return Reveal(
      id = scenario.id,
      title = scenario.title,
      level = scenario.level,
      topics = scenario.topics,
      answer = AnswerView(
        nature = answer.nature.map { option(Answers.NATURES, it) },
        location = answer.location.map { option(Answers.LOCATIONS, it) },
        signal = answer.signal.map { option(Answers.SIGNALS, it) },
        cause = cause(answer.cause),
        partial = answer.partial.map(::cause)
      ),
      explanation = scenario.explain(rendered(active)),
      steps = scenario.steps,
      evidence = scenario.evidence,
      cli = scenario.cli,
      guide = GuideIndex.pointers(scenario.doc),
      ruleOuts = options(active, scenario).causes.map { RuleOut(it, Answers.RULE_OUTS.getValue(it.key), it.key == answer.cause) }
    )
  }

  private fun option(options: List<Pair<String, String>>, key: String): Option = Option(key, Answers.label(options, key))

  private fun cause(key: String): Option = Option(key, Answers.label(Answers.CAUSES, key), Answers.CAUSE_GROUPS[key])

  private fun entry(active: ActiveExercise, title: String, level: Int, score: Double?, missed: List<String> = emptyList(), note: String = ""): HistoryEntry =
    HistoryEntry(
      n = active.n,
      id = active.id,
      mode = active.mode,
      title = title,
      level = level,
      score = score,
      hints = active.hints,
      minutes = minutesSince(active.started, now()),
      at = now(),
      missed = missed,
      note = note
    )

  private fun rendered(active: ActiveExercise): Params = Params(active.params, Format.hhmm(active.started, zone))

  companion object {
    const val INCIDENT = "incident"
    const val TOOLBOX = "toolbox"
    const val RAMP = "ramp"
    const val BENCH = "bench"
    val MODES = listOf(INCIDENT, TOOLBOX, RAMP, BENCH)

    /** The modes a new incident starts in. The ramp and the bench have their own commands. */
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
    const val RECENT_WINDOW = 5
    const val CORRECT = "correct"
    const val PARTIAL = "partial"
    const val WRONG = "wrong"
    const val CLEARED = "lab: fault cleared"
    const val TOOLBOX_RULES = "CLI only: do not open Grafana. `make shell` gives you a toolbox on the host. Prove the cause with commands."

    /** Where a browser on the host reaches the observability tools. */
    val HOST_LINKS: Map<String, String> = mapOf(
      "grafana" to "http://localhost:3000",
      "prometheus" to "http://localhost:9090",
      "pyroscope" to "http://localhost:4040"
    )
    private const val NEEDS_SEED = 1
    private const val NEEDS_ELAPSED_SECONDS = 3_600.0
    private const val DECOY_MAX_LEAD_SECONDS = 600
    private const val DECOY_MAX_LAG_SECONDS = 60
    private const val MARKER_MAX_LEAD_SECONDS = 180
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val TENTHS = 10.0

    /**
     * The container settings keys a scenario sets, from one render with fixed draws. The render is
     * an hour in, so a fault that starts late (`cpu_credits`) counts too.
     */
    fun dockerKeys(scenario: Scenario): Set<String> =
      runCatching {
        val params = Params(scenario.params(Random(NEEDS_SEED), ExerciseContext()), startedAt = "", elapsedSeconds = NEEDS_ELAPSED_SECONDS)
        scenario.faults(params).docker.values.flatMap { it.keys }.toSet()
      }.getOrDefault(emptySet())

    /** What an exercise whose ticket shows [symptom] offers. It reads nothing else, so it cannot give the scenario away. */
    fun answerOptions(symptom: Symptom): AnswerOptions =
      AnswerOptions(
        natures = Answers.NATURES.map { (key, label) -> Option(key, label) },
        locations = Answers.LOCATIONS.map { (key, label) -> Option(key, label) },
        signals = Answers.signalsFor(symptom).map { (key, label) -> Option(key, label) },
        causes = Answers.causesFor(symptom).map { (key, label) -> Option(key, label, Answers.CAUSE_GROUPS[key]) }
      )

    fun loadMultiplier(state: LabState): Double = (state.base["loadgen"]?.get("rate_multiplier") as? Number)?.toDouble() ?: 1.0

    /** Minutes to one decimal, the way history shows them. */
    fun minutesSince(started: Double, now: Double): Double = ((now - started) / SECONDS_PER_MINUTE * TENTHS).roundToInt() / TENTHS
  }
}

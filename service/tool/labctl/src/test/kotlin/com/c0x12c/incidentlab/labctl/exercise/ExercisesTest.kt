package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.SettleValues
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakeAnnotator
import com.c0x12c.incidentlab.labctl.ScriptedTerminal as HookedTerminal
import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Capabilities
import com.c0x12c.incidentlab.labctl.control.CapabilitiesFile
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.PlannedAnnotation
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.nio.file.Path
import java.time.ZoneId
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExercisesTest {

  private class ScriptedTerminal(private val answers: ArrayDeque<String?>) : Terminal {
    val lines = mutableListOf<String>()

    override fun out(line: String) {
      lines.add(line)
    }

    override fun ask(prompt: String): String? = answers.removeFirst()
  }

  private val probe = object : LabProbe {
    override fun cpuCount(): Int = 4

    override fun productDetailSeconds(): Double? = null

    override fun tenants(): Map<String, Int>? = null

    override fun serviceStatus(): Map<String, String?> = mapOf("api" to null)
  }

  private val annotator = FakeAnnotator()
  private val annotations get() = annotator.written.map { it.text to it.at }

  private val index = { options: List<Pair<String, String>>, key: String -> (options.indexOfFirst { it.first == key } + 1).toString() }

  private fun exercises(dir: Path, terminal: Terminal, capabilities: Capabilities = Capabilities.ALL, random: Random = Random(1)): Exercises =
    Exercises(StateStore(dir.resolve("state.json")), probe, annotator, terminal, random = random, now = { NOW }, capabilities = capabilities)

  /** What the open exercise offers, as the CLI prompts with it. */
  private fun offered(dir: Path): AnswerOptions =
    (ExerciseEngine(StateStore(dir.resolve("state.json")), probe, annotator, now = { NOW }).active() as Outcome.Done).value.active.options!!

  /** The numbers to type for these keys, in the open exercise's lists. */
  private fun picks(dir: Path, nature: String, location: String, signal: String, cause: String): List<String> {
    val options = offered(dir)
    return listOf(number(options.natures, nature), number(options.locations, location), number(options.signals, signal), number(options.causes, cause))
  }

  private fun number(options: List<Option>, key: String): String = (options.indexOfFirst { it.key == key }.also { check(it >= 0) { "$key is not offered" } } + 1).toString()

  private fun wrong(options: List<Option>, accepted: Set<String>): String = options.first { it.key !in accepted }.key

  private fun settleProbe(lag: () -> Double): FakePrometheus {
    val values = SettleValues(worst = { if (it == SettleGate.LAG) lag() else null }, past = { if (it == SettleGate.LAG) 60.0 else null })
    return FakePrometheus { expr, _ -> if (expr == SettleGate.UPTIME) 600.0 else values.answer(expr) }
  }

  @Test
  fun `make new waits for the lab to settle, prints what is left, then starts`(@TempDir dir: Path) {
    var lag = 30.0
    val sleeps = mutableListOf<Long>()
    val store = StateStore(dir.resolve("state.json"))
    store.save(LabState(lastEnded = NOW - 120))
    val probe = settleProbe { lag }
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = Exercises(store, probe, annotator, terminal, random = Random(1), now = { NOW }, gate = SettleGate(probe) { NOW }, sleep = {
      sleeps += it
      lag = 0.0
    })

    assertThat(exercises.newExercise(NewOptions(id = "pool_hold"))).isEqualTo(0)

    assertThat(sleeps).containsExactly(SettleWait.POLL_MILLIS)
    assertThat(terminal.lines).contains("  still settling: Kafka lag 30 s (want at most 5 s for 120 s)")
    assertThat(terminal.lines).contains("The lab is back to normal.")
    assertThat(store.load().active?.id).isEqualTo("pool_hold")
  }

  @Test
  fun `SETTLE=0 starts at once, and a forced new clears the open exercise before it waits`(@TempDir dir: Path) {
    var now = NOW
    val sleeps = mutableListOf<Long>()
    val store = StateStore(dir.resolve("state.json"))
    val probe = settleProbe { 0.0 }
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = Exercises(store, probe, annotator, terminal, random = Random(1), now = { now }, gate = SettleGate(probe) { now }, sleep = {
      sleeps += it
      now += 61
    })

    assertThat(exercises.newExercise(NewOptions(id = "pool_hold", settle = false))).isEqualTo(0)
    assertThat(sleeps).isEmpty()
    assertThat(terminal.lines.none { it.startsWith("Waiting for the lab to settle") }).isTrue()

    assertThat(exercises.newExercise(NewOptions(id = "oom", force = true))).isEqualTo(0)
    assertThat(sleeps).containsExactly(SettleWait.POLL_MILLIS)
    assertThat(terminal.lines).contains("  still settling: Time since the last exercise ended 0 s (want at least 60 s)")
    val state = store.load()
    assertThat(state.lastEnded).isEqualTo(NOW)
    assertThat(state.active?.id).isEqualTo("oom")
    assertThat(state.active?.baselineFrom).isEqualTo(NOW)
  }

  @Test
  fun `a reset ends the exercise, sets the load back and drops old marks, but keeps the history`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    val history = HistoryEntry(n = 1, id = "pool_hold", title = "t", level = 1, score = 4.0, hints = 0, minutes = 5.0, at = NOW)
    store.save(
      LabState(
        active = ActiveExercise(id = "pool_hold", params = emptyMap(), started = NOW, n = 2),
        history = listOf(history),
        base = mapOf("loadgen" to mapOf("rate_multiplier" to 2.0)),
        pendingAnnotationDeletes = listOf(PlannedAnnotation(id = 7, at = NOW + 60))
      )
    )
    val terminal = ScriptedTerminal(ArrayDeque())

    assertThat(exercises(dir, terminal).reset()).isEqualTo(0)

    assertThat(store.load()).isEqualTo(LabState(history = listOf(history), lastEnded = NOW))
    assertThat(terminal.lines.single()).isEqualTo("Fresh start: no exercise, load 1. History kept: 1 exercise(s).")
  }

  @Test
  fun `a full round of new, hint and answer is scored with the hint penalty`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)

    assertThat(exercises.newExercise(NewOptions(id = "pool_hold"))).isEqualTo(0)
    answers += picks(dir, "arch", "api_db", "pool", "pool_exhausted") + listOf("session time vs exec time", "y")
    val marker = annotations.single { it.first.startsWith("Deploy checkout-api v2.31") }
    assertThat(marker.second <= NOW && marker.second >= NOW - MARKER_LEAD_SECONDS).isTrue()
    assertThat(exercises.hint()).isEqualTo(0)
    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.any { it.startsWith("RESULT: 4.5/5") }).isTrue()
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active).isNull()
    assertThat(state.history.single().score).isEqualTo(4.5)
    assertThat(state.history.single().missed).isEmpty()
    assertThat(state.history.single().note).isEqualTo("session time vs exec time")
    assertThat(annotations.last().first).isEqualTo("lab: fault cleared")
  }

  @Test
  fun `a partial cause earns one point and a wrong answer none`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)
    val expected = ScenarioCatalog.byId.getValue("retry_storm").answer

    exercises.newExercise(NewOptions(id = "retry_storm"))
    val options = offered(dir)
    answers += picks(dir, wrong(options.natures, expected.nature), wrong(options.locations, expected.location), wrong(options.signals, expected.signal), expected.partial.first()) +
      listOf("", "n")
    exercises.answer()

    assertThat(terminal.lines.any { it.startsWith("RESULT: 1/5") }).isTrue()
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active).isNotNull()
    assertThat(state.history.single().missed).containsExactly("nature", "location", "signal", "cause (partial)")
    val tagged = ruleOutBlock(terminal.lines).filter { it.endsWith("]") }
    val partials = options.causes.filter { it.key in expected.partial }
    assertThat(tagged.filter { it.contains("[1 point") }).isEqualTo(
      partials.map { "     ${it.label}  [1 point${if (it.key == expected.partial.first()) ", your pick" else ""}]" }
    )
  }

  @Test
  fun `a second new without force is refused and reveal is not scored`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("y")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    assertThat(exercises.newExercise(NewOptions(id = "oom"))).isEqualTo(1)
    assertThat(exercises.reveal()).isEqualTo(0)

    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history.single().score).isNull()
    assertThat(state.active).isNull()
  }

  @Test
  fun `an answer is refused when another exercise opened while the prompts waited`(@TempDir dir: Path) {
    val terminal = HookedTerminal()
    val exercises = exercises(dir, terminal)
    exercises.newExercise(NewOptions(id = "pool_hold"))
    terminal.queue(*picks(dir, "arch", "api_db", "pool", "pool_exhausted").toTypedArray(), "", "y")
    terminal.beforeAnswer = { if (it == LAST_PICK) exercises(dir, HookedTerminal()).newExercise(NewOptions(id = "oom", force = true)) }

    assertThat(exercises.answer()).isEqualTo(1)

    assertThat(terminal.lines.last()).isEqualTo(Exercises.CHANGED)
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active!!.id).isEqualTo("oom")
    assertThat(state.active!!.answered).isFalse()
    assertThat(state.history).isEmpty()
  }

  @Test
  fun `the clear prompt only clears the exercise just answered, even when the input closes`(@TempDir dir: Path) {
    val terminal = HookedTerminal()
    val exercises = exercises(dir, terminal)
    exercises.newExercise(NewOptions(id = "pool_hold"))
    terminal.queue(*picks(dir, "arch", "api_db", "pool", "pool_exhausted").toTypedArray(), "")
    terminal.beforeAnswer = { if (it == CLEAR_PROMPT) exercises(dir, HookedTerminal()).newExercise(NewOptions(id = "oom", force = true)) }

    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.last()).isEqualTo(Exercises.NOT_CLEARED)
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active!!.id).isEqualTo("oom")
    assertThat(state.history.single().id).isEqualTo("pool_hold")
    assertThat(annotations.none { it.first == "lab: fault cleared" }).isTrue()
  }

  @Test
  fun `the prompts offer the ticket's lists, and the rule-out block marks the root cause and the pick, on reveal too`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)
    val scenario = ScenarioCatalog.byId.getValue("pool_hold")
    exercises.newExercise(NewOptions(id = scenario.id))
    val options = offered(dir)
    val symptom = scenario.symptom(Params(StateStore(dir.resolve("state.json")).load().active!!.params, Format.hhmm(NOW, ZoneId.systemDefault())))
    val pick = wrong(options.causes, setOf(scenario.answer.cause) + scenario.answer.partial)
    answers += picks(dir, "arch", "api_db", "pool", pick) + listOf("", "n")

    exercises.answer()

    assertThat(options.signals.map { it.key }).isEqualTo(Answers.signalsFor(symptom).map { it.first })
    assertThat(options.causes.map { it.key }).isEqualTo(Answers.causesFor(symptom).map { it.first })
    val prompted = terminal.lines.takeWhile { !it.startsWith("RESULT:") }.filter { NUMBERED.matches(it) }.map { it.substringAfter(". ") }
    assertThat(prompted).isEqualTo((options.natures + options.locations + options.signals + options.causes).map { it.label })
    val block = ruleOutBlock(terminal.lines)
    assertThat(block.filter { it.startsWith("       ") }).isEqualTo(options.causes.map { "       " + Answers.RULE_OUTS.getValue(it.key) })
    assertThat(block.filter { it.startsWith("  => ") }).containsExactly("  => ${Answers.label(Answers.CAUSES, scenario.answer.cause)}  [root cause]")
    assertThat(block.filter { it.endsWith("[your pick]") }).containsExactly("     ${Answers.label(Answers.CAUSES, pick)}  [your pick]")

    terminal.lines.clear()
    answers += listOf("y")
    exercises.reveal()
    val revealed = ruleOutBlock(terminal.lines)
    assertThat(revealed.filter { it.startsWith("  => ") }).containsExactly("  => ${Answers.label(Answers.CAUSES, scenario.answer.cause)}  [root cause]")
    assertThat(revealed.filter { it.endsWith("[your pick]") }).containsExactly("     ${Answers.label(Answers.CAUSES, pick)}  [your pick]")
  }

  @Test
  fun `a pick of the right cause is marked as both`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)
    exercises.newExercise(NewOptions(id = "oom"))
    answers += picks(dir, "arch", "container", "memory_restarts", "oom") + listOf("", "y")

    exercises.answer()

    assertThat(ruleOutBlock(terminal.lines).filter { it.startsWith("  => ") }).containsExactly("  => ${Answers.label(Answers.CAUSES, "oom")}  [root cause, your pick]")
  }

  private fun ruleOutBlock(lines: List<String>): List<String> =
    lines.dropWhile { !it.startsWith("RULE OUT THE LOOK-ALIKES") }.drop(1).takeWhile { it.isNotEmpty() }

  @Test
  fun `load changes the base override within bounds`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = exercises(dir, terminal)

    exercises.load(9.0)

    assertThat(StateStore(dir.resolve("state.json")).load().base.getValue("loadgen")["rate_multiplier"]).isEqualTo(6.0)
  }

  @Test
  fun `a bad pick asks again, causes print by group and the hint line shows the running penalty`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "cpu_throttle"))
    answers += listOf("", "abc", "99") + picks(dir, "arch", "container", "cgroup_cpu", "cpu_throttle") + listOf("", "y")
    val groups = offered(dir).causes.mapNotNull { it.group }.distinct()
    exercises.hint()
    exercises.hint()
    assertThat(terminal.lines).contains("Hint 1/3 (-0.5 point, -0.5 in total): " + ScenarioCatalog.byId.getValue("cpu_throttle").hints[0])
    assertThat(terminal.lines.any { it.startsWith("Hint 2/3 (-0.5 point, -1 in total)") }).isTrue()
    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.count { it == "  Enter a number from 1 to ${Answers.NATURES.size}." }).isEqualTo(3)
    assertThat(terminal.lines.takeWhile { !it.startsWith("RESULT:") }.filter { it.startsWith("  -- ") }).isEqualTo(groups.map { "  -- $it" })
    assertThat(terminal.lines.any { it.startsWith("RESULT: 4/5  (correct 5/5, 2 hint(s) used)") }).isTrue()
  }

  @Test
  fun `closed input in the middle of an answer scores nothing and keeps the exercise`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf(index(Answers.NATURES, "arch"), null)))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    assertThat(exercises.answer()).isEqualTo(1)

    assertThat(terminal.lines.last()).contains("Nothing was scored")
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history).hasSize(0)
    assertThat(state.active!!.answered).isFalse()
  }

  @Test
  fun `reveal after a kept answer adds no second history entry`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    answers += picks(dir, "arch", "container", "memory_restarts", "oom") + listOf("", "n", "y")
    exercises.answer()
    assertThat(exercises.answer()).isEqualTo(0)
    exercises.hint()
    assertThat(terminal.lines.last()).contains("already submitted")
    exercises.reveal()

    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.history.single().score).isEqualTo(5.0)
    assertThat(state.active).isNull()
  }

  @Test
  fun `an answer after a kept reveal is refused`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("n")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
    exercises.reveal()
    exercises.answer()

    assertThat(terminal.lines.last()).contains("already submitted")
    assertThat(StateStore(dir.resolve("state.json")).load().history.single().score).isNull()
  }

  @Test
  fun `history shows the note, the missed questions and the weak spots`(@TempDir dir: Path) {
    val answers = ArrayDeque<String?>()
    val terminal = ScriptedTerminal(answers)
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "retry_storm"))
    answers += picks(dir, "amplified", "client", "attempts_vs_requests", "downstream_errors") + listOf("saw pricing 503s", "y")
    exercises.answer()
    exercises.newExercise(NewOptions(id = "pool_hold"))
    answers += picks(dir, "arch", "api_db", "pool", "pool_exhausted") + listOf("", "y")
    exercises.answer()
    terminal.lines.clear()
    exercises.history()

    assertThat(terminal.lines.any { it.contains("4/5") && it.endsWith("Retry storm  missed: cause (partial)") }).isTrue()
    assertThat(terminal.lines).contains("     note: saw pricing 503s")
    assertThat(terminal.lines).contains("Average score: 4.50/5 over 2 exercise(s)")
    assertThat(terminal.lines.any { it.startsWith("By topic: ") && it.contains("red 4.0 (1)") && it.contains("layers 4.5 (2)") }).isTrue()
    assertThat(terminal.lines).contains("Most missed cause: ${Answers.label(Answers.CAUSES, "retry_storm")} (1 time(s))")
  }

  @Test
  fun `a ramp score gets its own average, apart from the incidents and their topics`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    store.save(
      store.load().copy(
        history = listOf(
          HistoryEntry(n = 1, id = "pool_hold", title = "Pool hold", level = 1, score = 4.0, hints = 0, minutes = 9.0, at = NOW),
          HistoryEntry(n = 2, id = "ramp", mode = "ramp", title = "Capacity ramp (pool_max = 3)", level = 0, score = 1.0, hints = 0, minutes = 9.0, at = NOW, missed = listOf("cause"))
        )
      )
    )
    val terminal = ScriptedTerminal(ArrayDeque())

    exercises(dir, terminal).history()

    assertThat(terminal.lines).contains("Average score: 4.00/5 over 1 exercise(s)")
    assertThat(terminal.lines).contains("Ramp average: 1.00/5 over 1 ramp(s)")
    assertThat(terminal.lines.none { it.startsWith("Most missed cause") }).isTrue()
  }

  @Test
  fun `toolbox mode picks only scenarios with commands and keeps Grafana out of the ticket`(@TempDir dir: Path) {
    val withCli = ScenarioCatalog.all.filter { it.cli.isNotEmpty() }.map { it.id }.toSet()
    repeat(PICKS) { round ->
      val terminal = ScriptedTerminal(ArrayDeque())
      val store = StateStore(dir.resolve("state-$round.json"))
      val exercises = Exercises(store, probe, annotator, terminal, random = Random(round), now = { NOW })

      assertThat(exercises.newExercise(NewOptions(mode = Exercises.TOOLBOX))).isEqualTo(0)

      val active = store.load().active!!
      assertThat(active.id in withCli).isTrue()
      assertThat(active.mode).isEqualTo(Exercises.TOOLBOX)
      assertThat(terminal.lines).contains(Exercises.TOOLBOX_RULES)
      assertThat(terminal.lines.none { it.contains("localhost:3000") }).isTrue()
    }
  }

  @Test
  fun `toolbox mode refuses an id without commands`(@TempDir dir: Path) {
    val withoutCli = ScenarioCatalog.all.first { it.cli.isEmpty() }
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = exercises(dir, terminal)

    assertThat(exercises.newExercise(NewOptions(id = withoutCli.id, mode = Exercises.TOOLBOX))).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("has no CLI proof yet")
    assertThat(StateStore(dir.resolve("state.json")).load().active).isNull()
  }

  @Test
  fun `the explanation prints the commands after the PromQL and history keeps the mode`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("y")))
    val exercises = exercises(dir, terminal)
    val scenario = ScenarioCatalog.byId.getValue("pool_hold")

    exercises.newExercise(NewOptions(id = scenario.id, mode = Exercises.TOOLBOX))
    exercises.reveal()

    val evidence = terminal.lines.indexOfFirst { it.startsWith("EVIDENCE TO LOOK FOR") }
    val commands = terminal.lines.indexOfFirst { it.startsWith("COMMANDS THAT PROVE IT") }
    assertThat(evidence in 0 until commands).isTrue()
    assertThat(terminal.lines.subList(commands + 1, commands + 1 + scenario.cli.size)).isEqualTo(scenario.cli.map { "  $ $it" })
    val entry = StateStore(dir.resolve("state.json")).load().history.single()
    assertThat(entry.mode).isEqualTo(Exercises.TOOLBOX)
    terminal.lines.clear()
    exercises.history()
    assertThat(terminal.lines.single { it.startsWith("#1") }).contains("toolbox")
  }

  @Test
  fun `an incident without commands prints no command block, and list tags the toolbox scenarios`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("y")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = ScenarioCatalog.all.first { it.cli.isEmpty() }.id))
    exercises.reveal()
    assertThat(terminal.lines.none { it.startsWith("COMMANDS THAT PROVE IT") }).isTrue()

    terminal.lines.clear()
    exercises.list()
    for (scenario in ScenarioCatalog.all) {
      val line = terminal.lines.single { it.startsWith("  - ${scenario.id} ") }
      assertThat(line.endsWith("[toolbox]")).isEqualTo(scenario.cli.isNotEmpty())
    }
  }

  @Test
  fun `every title fits the list column`() {
    for (scenario in ScenarioCatalog.all) {
      assertThat(scenario.title.length <= Exercises.TITLE_COLUMN).isTrue()
    }
  }

  @Test
  fun `an exercise whose params no longer render is reported, not crashed on`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val store = StateStore(dir.resolve("state.json"))
    store.save(LabState(active = ActiveExercise(id = "client_outage", params = mapOf("tenant" to "ios"), started = NOW, n = 1)))
    val exercises = exercises(dir, terminal)

    assertThat(exercises.status()).isEqualTo(0)
    assertThat(exercises.answer()).isEqualTo(1)
    assertThat(exercises.hint()).isEqualTo(1)
    assertThat(terminal.lines.count { it.contains("older version of the lab") }).isEqualTo(3)
    assertThat(exercises.clear()).isEqualTo(0)
    assertThat(store.load().active).isNull()
  }

  @Test
  fun `a scenario counts the container settings it sets, a late fault included`() {
    val needs = { id: String -> ExerciseEngine.dockerKeys(ScenarioCatalog.byId.getValue(id)) }

    assertThat(needs("cpu_throttle")).isEqualTo(setOf("cpus"))
    assertThat(needs("cpu_credits")).isEqualTo(setOf("cpus"))
    assertThat(needs("packet_loss")).isEqualTo(setOf("netem"))
    assertThat(needs("iops_limit")).isEqualTo(setOf("write_iops"))
    assertThat(needs("pool_hold")).isEmpty()
  }

  @Test
  fun `a random pick skips every scenario that needs a setting the controller cannot apply`(@TempDir dir: Path) {
    val onlyCpus = Capabilities { setOf("cpus") }
    val terminal = ScriptedTerminal(ArrayDeque())
    val store = StateStore(dir.resolve("state.json"))
    val picked = (0 until MANY_PICKS).map { round ->
      exercises(dir, terminal, onlyCpus, Random(round)).newExercise(NewOptions(force = true))
      store.load().active!!.id
    }.toSet()

    assertThat(picked.none { it == "iops_limit" || it == "packet_loss" }).isTrue()
    assertThat(picked.all { (ExerciseEngine.dockerKeys(ScenarioCatalog.byId.getValue(it)) - "cpus").isEmpty() }).isTrue()
    assertThat("cpu_throttle" in picked || "cpu_credits" in picked).isTrue()
  }

  @Test
  fun `an id the controller cannot run is refused and nothing starts`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = exercises(dir, terminal, Capabilities { setOf("cpus") })

    assertThat(exercises.newExercise(NewOptions(id = "iops_limit"))).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("'iops_limit' needs container settings this lab's controller cannot apply (write_iops)")
    assertThat(StateStore(dir.resolve("state.json")).load().active).isNull()
    assertThat(exercises.newExercise(NewOptions(id = "cpu_throttle"))).isEqualTo(0)
  }

  @Test
  fun `with no controller every setting is applied, and a topic with nothing runnable says why`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val file = CapabilitiesFile.beside(dir.resolve("state.json"))

    assertThat(file.dockerKeys()).isNull()
    assertThat(exercises(dir, terminal, file).newExercise(NewOptions(id = "iops_limit"))).isEqualTo(0)

    file.write(emptySet())
    val none = exercises(dir, terminal, file)
    val dockerOnly = Answers.TOPICS.first { topic ->
      ScenarioCatalog.all.filter { topic in it.topics }.let { matching -> matching.isNotEmpty() && matching.all { ExerciseEngine.dockerKeys(it).isNotEmpty() } }
    }
    assertThat(none.newExercise(NewOptions(id = "packet_loss", force = true))).isEqualTo(1)
    assertThat(none.newExercise(NewOptions(topic = dockerOnly, force = true))).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("Every exercise that matches needs")
  }

  @Test
  fun `away from load 1 a random pick skips the scenarios tuned for the base load, and their id is refused`(@TempDir dir: Path) {
    val baseLoad = ScenarioCatalog.all.filter { it.baseLoad }.map { it.id }.toSet()
    val terminal = ScriptedTerminal(ArrayDeque())
    val store = StateStore(dir.resolve("state.json"))
    exercises(dir, terminal).load(1.5)
    val picked = (0 until MANY_PICKS).map { round ->
      exercises(dir, terminal, random = Random(round)).newExercise(NewOptions(force = true))
      store.load().active!!.id
    }.toSet()

    assertThat(baseLoad.isNotEmpty()).isTrue()
    assertThat(picked.intersect(baseLoad)).isEmpty()
    assertThat(exercises(dir, terminal).newExercise(NewOptions(id = baseLoad.first(), force = true))).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("Background load is 1.5 x")
    exercises(dir, terminal).load(1.0)
    assertThat(exercises(dir, terminal).newExercise(NewOptions(id = baseLoad.first(), force = true))).isEqualTo(0)
  }

  private companion object {
    const val MANY_PICKS = 200
    const val NOW = 1_700_000_000.0
    const val MARKER_LEAD_SECONDS = 180
    const val PICKS = 20
    const val LAST_PICK = 4
    const val CLEAR_PROMPT = 6
    val NUMBERED = Regex("^  [ 0-9][0-9]\\. .*")
  }
}

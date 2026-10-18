package com.c0x12c.incidentlab.labctl.exercise

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
import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.nio.file.Path
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

  private val annotations = mutableListOf<Pair<String, Double>>()
  private val annotator = Annotator { text, _, at -> annotations.add(text to at) }

  private val index = { options: List<Pair<String, String>>, key: String -> (options.indexOfFirst { it.first == key } + 1).toString() }

  private fun exercises(dir: Path, terminal: Terminal): Exercises =
    Exercises(StateStore(dir.resolve("state.json")), probe, annotator, terminal, random = Random(1), now = { NOW })

  private fun picks(nature: String, location: String, signal: String, cause: String): List<String> =
    listOf(index(Answers.NATURES, nature), index(Answers.LOCATIONS, location), index(Answers.SIGNALS, signal), index(Answers.CAUSES, cause))

  @Test
  fun `a full round of new, hint and answer is scored with the hint penalty`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(picks("arch", "api_db", "pool", "pool_exhausted") + listOf("session time vs exec time", "y")))
    val exercises = exercises(dir, terminal)

    assertThat(exercises.newExercise(NewOptions(id = "pool_hold"))).isEqualTo(0)
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
    val terminal = ScriptedTerminal(ArrayDeque(picks("load", "host", "host_cpu", "downstream_errors") + listOf("", "n")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "retry_storm"))
    exercises.answer()

    assertThat(terminal.lines.any { it.startsWith("RESULT: 1/5") }).isTrue()
    val state = StateStore(dir.resolve("state.json")).load()
    assertThat(state.active).isNotNull()
    assertThat(state.history.single().missed).containsExactly("nature", "location", "signal", "cause (partial)")
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
  fun `load changes the base override within bounds`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque())
    val exercises = exercises(dir, terminal)

    exercises.load(9.0)

    assertThat(StateStore(dir.resolve("state.json")).load().base.getValue("loadgen")["rate_multiplier"]).isEqualTo(6.0)
  }

  @Test
  fun `a bad pick asks again, causes print by group and the hint line shows the running penalty`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal(ArrayDeque(listOf("", "abc", "99") + picks("arch", "container", "cgroup_cpu", "cpu_throttle") + listOf("", "y")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "cpu_throttle"))
    exercises.hint()
    exercises.hint()
    assertThat(terminal.lines).contains("Hint 1/3 (-0.5 point, -0.5 in total): " + ScenarioCatalog.byId.getValue("cpu_throttle").hints[0])
    assertThat(terminal.lines.any { it.startsWith("Hint 2/3 (-0.5 point, -1 in total)") }).isTrue()
    assertThat(exercises.answer()).isEqualTo(0)

    assertThat(terminal.lines.count { it == "  Enter a number from 1 to ${Answers.NATURES.size}." }).isEqualTo(3)
    assertThat(terminal.lines.count { it.startsWith("  -- ") }).isEqualTo(Answers.CAUSE_GROUPS.values.toSet().size)
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
    val terminal = ScriptedTerminal(ArrayDeque(picks("arch", "container", "memory_restarts", "oom") + listOf("", "n", "y")))
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "oom"))
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
    val terminal = ScriptedTerminal(
      ArrayDeque(
        picks("amplified", "client", "attempts_vs_requests", "downstream_errors") + listOf("saw pricing 503s", "y") +
          picks("arch", "api_db", "pool", "pool_exhausted") + listOf("", "y")
      )
    )
    val exercises = exercises(dir, terminal)

    exercises.newExercise(NewOptions(id = "retry_storm"))
    exercises.answer()
    exercises.newExercise(NewOptions(id = "pool_hold"))
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

  private companion object {
    const val NOW = 1_700_000_000.0
    const val MARKER_LEAD_SECONDS = 180
    const val PICKS = 20
  }
}

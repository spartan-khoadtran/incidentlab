package com.c0x12c.incidentlab.labctl.ramp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.ScriptedTerminal
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RampExerciseTest {

  private var clock = START
  private val annotations = mutableListOf<String>()
  private val annotator = Annotator { text, _, _ -> annotations += text }

  /** A pool of 3 as Prometheus would record it: p99 bends at step 6 (200 rps), throughput tops out near 203 rps. */
  private val prometheus = FakePrometheus { expr, at ->
    val step = ((at - START) / EVERY).roundToInt() - 1
    val p99 = listOf(0.080, 0.085, 0.090, 0.110, 0.180, 0.420, 1.900, 2.000)
    val achieved = listOf(50.0, 75.0, 100.0, 125.0, 149.0, 196.0, 203.0, 201.0)
    val errors = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.002, 0.06, 0.25)
    when {
      step !in 0..7 -> null
      expr.startsWith("histogram_quantile") -> p99[step]
      expr.contains("status=~\"2..\"") -> achieved[step]
      expr.contains("outcome!~") -> errors[step]
      expr.startsWith("sum(rate(client_requests_total") -> RampPlan.rps(step).toDouble()
      else -> null
    }
  }

  private fun store(dir: Path) = StateStore(dir.resolve("state.json"))

  private fun ramp(dir: Path, terminal: ScriptedTerminal, probe: FakePrometheus = prometheus, random: Random = Random(1)) =
    RampExercise(store(dir), probe, annotator, terminal, random = random, now = { clock })

  private fun exercises(dir: Path, terminal: ScriptedTerminal, ramp: RampExercise) =
    Exercises(store(dir), prometheus, annotator, terminal, now = { clock }, modes = listOf(ramp))

  /** Pins the hidden setup to a pool of 3, the setup the fake table above records. */
  private fun pinPoolOfThree(dir: Path) {
    val state = store(dir).load()
    val active = state.active!!
    store(dir).save(state.copy(active = active.copy(params = mapOf("setup" to RampCatalog.POOL, "pool_max" to 3, "cpus" to 2.0, "every" to EVERY))))
  }

  @Test
  fun `the ramp is refused while an exercise is open or the load is not 1`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    exercises(dir, terminal, ramp(dir, terminal)).load(1.5)
    assertThat(ramp(dir, terminal).start(RampOptions())).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("make load X=1")

    exercises(dir, terminal, ramp(dir, terminal)).load(1.0)
    assertThat(ramp(dir, terminal).start(RampOptions(every = EVERY))).isEqualTo(0)
    assertThat(ramp(dir, terminal).start(RampOptions(every = EVERY))).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("still open")
  }

  @Test
  fun `start saves a ramp, marks every step and prints the plan`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    ramp(dir, terminal).start(RampOptions(every = EVERY))

    val active = store(dir).load().active!!
    assertThat(active.id).isEqualTo(RampCatalog.ID)
    assertThat(active.mode).isEqualTo(RampExercise.MODE)
    assertThat(active.params["every"]).isEqualTo(EVERY)
    assertThat(annotations).hasSize(RampPlan.size + 1)
    assertThat(annotations.first()).isEqualTo("ramp step 1: 50 rps")
    assertThat(terminal.lines.any { it.contains("50, 75, 100, 125, 150, 200, 250, 300 rps") }).isTrue()

    clock = START + 2.5 * EVERY
    terminal.lines.clear()
    exercises(dir, terminal, ramp(dir, terminal)).status()
    assertThat(terminal.lines.first()).isEqualTo("Capacity ramp #1: step 3 of 8 at 100 rps, 30 s into the step, 5.5 min to the end.")
  }

  @Test
  fun `an answer before the first step ends is refused`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    val ramp = ramp(dir, terminal)
    ramp.start(RampOptions(every = EVERY))
    clock = START + 20

    assertThat(exercises(dir, terminal, ramp).answer()).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("40 s to go")
  }

  @Test
  fun `an answer is scored from the step table and kept in history as a ramp`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal("abc", "200", "205", "1", "read p99 at each annotation", "y")
    val ramp = ramp(dir, terminal)
    ramp.start(RampOptions(every = EVERY))
    pinPoolOfThree(dir)
    clock = START + RampPlan.totalSeconds(EVERY) + 30

    assertThat(exercises(dir, terminal, ramp).answer()).isEqualTo(0)

    assertThat(terminal.lines).contains("  Enter a number of rps, for example 150.")
    assertThat(terminal.lines.any { it.startsWith("     6   200 rps   196 rps     420 ms     0.2%  <- knee") }).isTrue()
    assertThat(terminal.lines).contains("  Measured knee: 200 rps (step 6). Highest throughput: 203 rps.")
    val state = store(dir).load()
    assertThat(state.active).isNull()
    val entry = state.history.single()
    assertThat(entry.mode).isEqualTo(RampExercise.MODE)
    assertThat(entry.note).isEqualTo("read p99 at each annotation")
    assertThat(entry.score).isEqualTo(5.0)
    assertThat(entry.title).isEqualTo("Capacity ramp (pool_max = 3)")
    assertThat(terminal.lines.any { it.startsWith("RESULT: 5/5") }).isTrue()
    assertThat(annotations.last()).isEqualTo("lab: ramp cleared")
  }

  @Test
  fun `with Prometheus down nothing is asked and nothing is scored`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal("200", "200", "1")
    val down = FakePrometheus(up = false) { _, _ -> 1.0 }
    val ramp = ramp(dir, terminal, down)
    ramp.start(RampOptions(every = EVERY))
    clock = START + RampPlan.totalSeconds(EVERY)

    assertThat(exercises(dir, terminal, ramp).answer()).isEqualTo(1)

    assertThat(terminal.lines.last()).contains("Prometheus did not answer")
    val state = store(dir).load()
    assertThat(state.history).hasSize(0)
    assertThat(state.active).isNotNull()
  }

  @Test
  fun `reveal is not scored and history names the ramp mode after the title`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal("y")
    val ramp = ramp(dir, terminal)
    store(dir).save(
      store(dir).load().copy(
        history = listOf(HistoryEntry(n = 1, id = "oom", title = "Out of memory", level = 1, score = 5.0, hints = 0, minutes = 9.0, at = START))
      )
    )
    ramp.start(RampOptions(every = EVERY))
    clock = START + 3 * EVERY
    val exercises = exercises(dir, terminal, ramp)

    assertThat(exercises.reveal()).isEqualTo(0)
    assertThat(terminal.lines.any { it.startsWith("CAPACITY RAMP #2: Capacity ramp (") && it.endsWith("(not scored)") }).isTrue()
    assertThat(terminal.lines.count { it.contains("<- knee") }).isEqualTo(0)
    terminal.lines.clear()
    exercises.history()

    assertThat(store(dir).load().history.map { it.score }).containsExactly(5.0, null)
    assertThat(terminal.lines.any { it.endsWith("hint(s)  Out of memory") }).isTrue()
    assertThat(terminal.lines.any { it.contains("hint(s)  Capacity ramp (") && it.endsWith(") (ramp)") }).isTrue()
  }

  private companion object {
    const val START = 1_700_000_000.0
    const val EVERY = 60
  }
}

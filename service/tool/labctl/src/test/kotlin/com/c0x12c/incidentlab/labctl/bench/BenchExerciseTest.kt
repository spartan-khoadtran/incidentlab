package com.c0x12c.incidentlab.labctl.bench

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
import com.c0x12c.incidentlab.labctl.control.DesiredState
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BenchExerciseTest {

  private var clock = START
  private val annotations = mutableListOf<Pair<String, Double>>()
  private val annotator = Annotator { text, _, at -> annotations += text to at }

  /** The open load before the bench, then 20 clients held at 267 rps by a full pool. */
  private val prometheus = FakePrometheus { expr, at ->
    val bench = at > START
    when {
      expr.startsWith("sum(rate(client_requests_total[") -> if (bench) 267.0 else 50.0
      expr.startsWith("sum(rate(http_server_requests_total{job=\"api\"}[") -> if (bench) 266.0 else 50.0
      expr.startsWith("histogram_quantile(0.5, sum by (le) (rate(client") -> if (bench) 0.040 else 0.012
      expr.startsWith("histogram_quantile(0.99, sum by (le) (rate(client") -> if (bench) 0.310 else 0.090
      expr.startsWith("histogram_quantile(0.5, sum by (le) (rate(http") -> if (bench) 0.036 else 0.009
      expr.startsWith("histogram_quantile(0.99, sum by (le) (rate(http") -> if (bench) 0.300 else 0.085
      expr.startsWith("sum(rate(client_duration_seconds_sum") -> if (bench) 0.075 else 0.025
      expr.startsWith("avg_over_time(http_server_inflight") -> if (bench) 19.6 else 1.2
      expr.contains("outcome!~") || expr.contains("status=~\"5..\"") -> 0.0
      else -> null
    }
  }

  private fun store(dir: Path) = StateStore(dir.resolve("state.json"))

  private fun bench(dir: Path, terminal: ScriptedTerminal, probe: FakePrometheus = prometheus) =
    BenchExercise(store(dir), probe, annotator, terminal, now = { clock })

  private fun exercises(dir: Path, terminal: ScriptedTerminal, bench: BenchExercise) =
    Exercises(store(dir), prometheus, annotator, terminal, now = { clock }, modes = listOf(bench))

  @Test
  fun `the bench runs closed clients for its time, then the open load comes back`() {
    val params = BenchCatalog.params(20, 120)

    assertThat(BenchCatalog.faults(BenchCatalog.ID, Params(params, "", 0.0))).isEqualTo(FaultSpec(services = mapOf("loadgen" to mapOf("closed_clients" to 20))))
    assertThat(BenchCatalog.faults(BenchCatalog.ID, Params(params, "", 119.9))!!.services.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("closed_clients" to 20))
    assertThat(BenchCatalog.faults(BenchCatalog.ID, Params(params, "", 120.0))).isEqualTo(FaultSpec())
    assertThat(BenchCatalog.faults("ramp", Params(params, "", 0.0))).isNull()
    assertThat(BenchCatalog.ID in ScenarioCatalog.byId.keys).isEqualTo(false)
  }

  @Test
  fun `the daemon reads the bench from the state file like any exercise`(@TempDir dir: Path) {
    bench(dir, ScriptedTerminal()).start(BenchOptions(clients = 8, seconds = 60))
    val state = store(dir).load()

    assertThat(DesiredState.of(state, now = START + 30).faults.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("closed_clients" to 8))
    assertThat(DesiredState.of(state, now = START + 61).faults.getValue("loadgen")).isEqualTo(emptyMap<String, Any?>())
  }

  @Test
  fun `start is refused while another exercise is open, and marks the bench window`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    val bench = bench(dir, terminal)

    assertThat(bench.start(BenchOptions())).isEqualTo(0)
    val active = store(dir).load().active!!
    assertThat(active.mode).isEqualTo(BenchExercise.MODE)
    assertThat(active.params).isEqualTo(mapOf<String, Any>("clients" to 20, "seconds" to 120))
    assertThat(annotations.map { it.second }).containsExactly(START, START + 120)

    store(dir).save(store(dir).load().copy(active = active.copy(id = "oom", mode = "incident")))
    assertThat(bench.start(BenchOptions())).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("still open")
  }

  @Test
  fun `during the bench, answer and bench again only say how long is left`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    val bench = bench(dir, terminal)
    bench.start(BenchOptions())
    clock = START + 50

    assertThat(exercises(dir, terminal, bench).answer()).isEqualTo(1)
    assertThat(terminal.lines.last()).isEqualTo("Closed-loop bench #1: 20 clients, 70 s left.")
    assertThat(bench.start(BenchOptions())).isEqualTo(1)
    assertThat(prometheus.queries).hasSize(0)
  }

  @Test
  fun `after the bench, the report compares the two windows and closes the bench without a score`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    val bench = bench(dir, terminal)
    bench.start(BenchOptions())
    clock = START + 200

    assertThat(bench.start(BenchOptions())).isEqualTo(0)

    assertThat(terminal.lines).contains("  client  rate                  50 rps       267 rps")
    assertThat(terminal.lines).contains("  api     in flight                1.2          19.6")
    assertThat(terminal.lines.any { it.contains("20 = 267 rps x R, so R = 20 / 267 = 75 ms. The measured mean was 75 ms.") }).isTrue()
    assertThat(terminal.lines.any { it.contains("Coordinated omission") }).isTrue()
    assertThat(prometheus.queries).contains("sum(rate(client_requests_total[120s]))")
    assertThat(prometheus.queries).contains("sum(rate(client_requests_total[110s]))")
    val state = store(dir).load()
    assertThat(state.active).isNull()
    val entry = state.history.single()
    assertThat(entry.mode).isEqualTo(BenchExercise.MODE)
    assertThat(entry.score).isNull()
    assertThat(entry.title).isEqualTo("Closed-loop bench (20 clients, 120 s)")
  }

  @Test
  fun `with no data from Prometheus the bench stays open`(@TempDir dir: Path) {
    val terminal = ScriptedTerminal()
    val bench = bench(dir, terminal, FakePrometheus(up = false) { _, _ -> 1.0 })
    bench.start(BenchOptions())
    clock = START + 200

    assertThat(bench.answer(store(dir).load())).isEqualTo(1)
    assertThat(terminal.lines.last()).contains("Prometheus did not answer")
    assertThat(store(dir).load().active).isNotNull()
  }

  private companion object {
    const val START = 1_700_000_000.0
  }
}

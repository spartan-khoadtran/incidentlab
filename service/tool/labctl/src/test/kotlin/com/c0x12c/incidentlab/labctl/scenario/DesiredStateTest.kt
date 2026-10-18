package com.c0x12c.incidentlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.c0x12c.incidentlab.labctl.control.DesiredState
import com.c0x12c.incidentlab.labctl.scenario.catalog.LoadScenarios
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import org.junit.jupiter.api.Test

class DesiredStateTest {

  @Test
  fun `with no exercise every service gets an empty override and the api keeps 2 cores`() {
    val desired = DesiredState.of(LabState())

    assertThat(desired.faults.keys).isEqualTo(setOf("api", "pricing", "worker", "loadgen", "noisy"))
    assertThat(desired.faults.values.all { it.isEmpty() }).isEqualTo(true)
    assertThat(desired.docker).isEqualTo(mapOf("api" to mapOf("cpus" to 2.0)))
  }

  @Test
  fun `the base load multiplier and the active scenario merge`() {
    val state = LabState(
      active = ActiveExercise(id = "cpu_throttle", params = mapOf("cpus" to 0.3), started = 0.0, n = 1),
      base = mapOf("loadgen" to mapOf("rate_multiplier" to 1.5))
    )

    val desired = DesiredState.of(state)

    assertThat(desired.faults.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("rate_multiplier" to 1.5))
    assertThat(desired.docker).isEqualTo(mapOf("api" to mapOf("cpus" to 0.3)))
  }

  @Test
  fun `the lag exercise rolls the worker back after its fix time and keeps the backfill`() {
    val state = LabState(active = ActiveExercise(id = "lag_after_fix", params = mapOf("slow_ms" to 100, "extra" to 85, "fix_after" to 150), started = 1_000.0, n = 1))

    val before = DesiredState.of(state, now = 1_100.0)
    val after = DesiredState.of(state, now = 1_200.0)

    assertThat(before.faults.getValue("worker")).isEqualTo(mapOf<String, Any?>("process_ms" to 100))
    assertThat(after.faults.getValue("worker")).isEqualTo(emptyMap<String, Any?>())
    assertThat(after.faults.getValue("api")).isEqualTo(mapOf<String, Any?>("extra_events_rps" to 85))
  }

  @Test
  fun `a source after the catalog answers for an id the catalog does not know`() {
    val ramp = FaultSource { id, p -> if (id == "steps") FaultSpec(services = mapOf("loadgen" to mapOf("surge_multiplier" to 1 + p.elapsedSeconds / p.int("every")))) else null }
    val state = LabState(active = ActiveExercise(id = "steps", params = mapOf("every" to 60), started = 1_000.0, n = 1))

    val desired = DesiredState.of(state, sources = listOf(ScenarioCatalog, ramp), now = 1_120.0)

    assertThat(desired.faults.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("surge_multiplier" to 3.0))
    assertThat(DesiredState.of(state, sources = listOf(ScenarioCatalog), now = 1_120.0).faults.getValue("loadgen")).isEqualTo(emptyMap<String, Any?>())
  }

  @Test
  fun `the catalog is a source for its own ids only`() {
    val id = LoadScenarios.all.first().id
    assertThat(ScenarioCatalog.faults("nope", Params(emptyMap(), ""))).isEqualTo(null)
    assertThat(ScenarioCatalog.faults(id, Params(ScenarioCatalog.byId.getValue(id).params(kotlin.random.Random(1), ExerciseContext()), "")) != null).isEqualTo(true)
  }

  @Test
  fun `an unknown active id is ignored instead of crashing the daemon`() {
    val state = LabState(active = ActiveExercise(id = "gone", params = emptyMap(), started = 0.0, n = 1))

    assertThat(DesiredState.of(state).faults.getValue("api")).isEqualTo(emptyMap<String, Any?>())
  }

  @Test
  fun `params from an older catalog that no longer render are ignored too`() {
    val state = LabState(active = ActiveExercise(id = "cpu_throttle", params = emptyMap(), started = 0.0, n = 1))

    val desired = DesiredState.of(state)

    assertThat(desired.faults.getValue("api")).isEqualTo(emptyMap<String, Any?>())
    assertThat(desired.docker).isEqualTo(mapOf("api" to mapOf("cpus" to 2.0)))
  }
}

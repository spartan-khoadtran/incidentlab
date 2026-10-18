package com.c0x12c.incidentlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.c0x12c.incidentlab.labctl.control.DesiredState
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
  fun `the hot code path sends render_ms to api only and keeps the api at 2 cores`() {
    val state = LabState(active = ActiveExercise(id = "hot_code_path", params = mapOf("render" to 27), started = 0.0, n = 1))

    val desired = DesiredState.of(state)

    assertThat(desired.faults.getValue("api")).isEqualTo(mapOf<String, Any?>("render_ms" to 27))
    assertThat(desired.faults.filterKeys { it != "api" }.values.all { it.isEmpty() }).isEqualTo(true)
    assertThat(desired.docker).isEqualTo(mapOf("api" to mapOf("cpus" to 2.0)))
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

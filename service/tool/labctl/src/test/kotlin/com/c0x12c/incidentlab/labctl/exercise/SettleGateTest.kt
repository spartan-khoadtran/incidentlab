package com.c0x12c.incidentlab.labctl.exercise

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.ERRORS
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.FAULT_BYTES
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.LAG
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.P99
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.UPTIME
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.WAITING
import com.c0x12c.incidentlab.labctl.state.LabState
import org.junit.jupiter.api.Test

class SettleGateTest {

  private val now = 10_000.0
  private val healthy = mapOf(UPTIME to 600.0, LAG to 0.4, ERRORS to 0.0, P99 to 0.3, "$P99 offset 1m" to 0.3, WAITING to 0.0, FAULT_BYTES to 0.0)

  private fun gate(values: Map<String, Double> = healthy, up: Boolean = true) = SettleGate(FakePrometheus(up) { expr, _ -> values[expr] }) { now }

  private fun endedAgo(seconds: Double?) = LabState(lastEnded = seconds?.let { now - it })

  private fun failing(status: SettleStatus): List<String> = status.checks.filterNot { it.ok }.map { it.name }

  @Test
  fun `a lab back to normal is settled, and every check shows its value`() {
    val status = gate().status(endedAgo(120.0))

    assertThat(status.settled).isTrue()
    assertThat(status.checks.size).isEqualTo(7)
    assertThat(status.checks.single { it.name == "Kafka lag" }.value).isEqualTo("0.4 s")
    assertThat(status.waitedSeconds).isEqualTo(120.0)
    assertThat(gate().refusal(endedAgo(120.0))).isNull()
  }

  @Test
  fun `each check holds the start on its own`() {
    assertThat(failing(gate().status(endedAgo(30.0)))).isEqualTo(listOf("Time since the last exercise ended"))
    val cases = mapOf(
      UPTIME to (60.0 to "Every service up since its last start"),
      LAG to (12.0 to "Kafka lag"),
      ERRORS to (3.0 to "Errors users see"),
      WAITING to (2.0 to "Requests waiting inside api"),
      FAULT_BYTES to (5_000_000.0 to "Memory the last fault kept in api")
    )
    for ((expr, case) in cases) {
      assertThat(failing(gate(healthy + (expr to case.first)).status(endedAgo(120.0)))).isEqualTo(listOf(case.second))
    }
  }

  @Test
  fun `user p99 must be steady, so a falling or a rising tail waits and a fast one passes`() {
    fun steady(current: Double, before: Double) = gate(healthy + mapOf(P99 to current, "$P99 offset 1m" to before)).status(endedAgo(120.0)).settled

    assertThat(steady(0.5, 1.2)).isFalse()
    assertThat(steady(0.9, 0.5)).isFalse()
    assertThat(steady(0.5, 0.45)).isTrue()
    assertThat(steady(0.05, 0.18)).isTrue()
  }

  @Test
  fun `a value Prometheus cannot give never holds the start`() {
    val status = gate(up = false).status(endedAgo(120.0))

    assertThat(status.settled).isTrue()
    assertThat(status.checks.filter { it.value == null }.size).isEqualTo(6)
  }

  @Test
  fun `the gate gives up after five minutes, and a state with no end never waits`() {
    val busy = healthy + (LAG to 30.0)

    assertThat(gate(busy).refusal(endedAgo(200.0)) is Refusal.LabSettling).isTrue()
    val late = gate(busy).status(endedAgo(SettleGate.CAP_SECONDS + 1))
    assertThat(late.settled).isFalse()
    assertThat(late.gaveUp).isTrue()
    assertThat(gate(busy).refusal(endedAgo(SettleGate.CAP_SECONDS + 1))).isNull()
    assertThat(gate(busy).status(endedAgo(null)).gaveUp).isTrue()
  }
}

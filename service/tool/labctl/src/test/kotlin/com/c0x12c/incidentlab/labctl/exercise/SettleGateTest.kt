package com.c0x12c.incidentlab.labctl.exercise

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.SettleValues
import com.c0x12c.incidentlab.labctl.withRange
import com.c0x12c.incidentlab.labctl.control.Sample
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.ERRORS
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.LAG
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.P99
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.RSS
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.UPTIME
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.WAITING
import com.c0x12c.incidentlab.labctl.state.LabState
import org.junit.jupiter.api.Test

class SettleGateTest {

  private val now = 10_000.0
  private val normal = mapOf(LAG to 0.4, ERRORS to 0.0, P99 to 0.1, WAITING to 0.0, RSS to 400e6)

  private fun gate(worst: Map<String, Double> = normal, past: Map<String, Double>? = null, uptime: Double? = 600.0, range: List<Sample> = emptyList()): SettleGate {
    val values = SettleValues(worst = { worst[it] }, normal = { normal[it] }, now = { worst[it] }, past = { (past ?: worst)[it] }, range = { range })
    return SettleGate(FakePrometheus { expr, _ -> if (expr == UPTIME) uptime else values.answer(expr) }.withRange(values)) { now }
  }

  private fun state(endedAgo: Double?, startedAgo: Double? = 600.0) = LabState(lastEnded = endedAgo?.let { now - it }, lastStarted = startedAgo?.let { now - it })

  private fun failing(status: SettleStatus): List<String> = status.checks.filterNot { it.ok }.map { it.name }

  @Test
  fun `a lab back to its normal is settled, and a check shows its value against normal`() {
    val status = gate().status(state(150.0))

    assertThat(status.settled).isTrue()
    assertThat(status.checks.single { it.name == "User p99" }.value).isEqualTo("0.1 s (normal 0.1 s)")
    assertThat(status.checks.single { it.name == "User p99" }.target).isEqualTo("at most 0.2 s for 120 s")
  }

  @Test
  fun `each check holds the start on its own, against the lab's own normal`() {
    assertThat(failing(gate().status(state(30.0)))).isEqualTo(listOf("Time since the last exercise ended"))
    assertThat(failing(gate(uptime = 60.0).status(state(150.0)))).isEqualTo(listOf("Every service up since its last start"))
    assertThat(failing(gate(uptime = 0.0).status(state(150.0)))).isEqualTo(listOf("Every service up since its last start"))
    val cases = mapOf(LAG to 12.0, ERRORS to 3.0, P99 to 0.5, WAITING to 2.0, RSS to 600e6)
    for ((expr, bad) in cases) {
      assertThat(failing(gate(normal + (expr to bad)).status(state(150.0))).size).isEqualTo(1)
    }
  }

  @Test
  fun `a busy normal raises the limits, so a loaded lab is not held for ever`() {
    val busy = normal + (P99 to 0.9) + (WAITING to 2.0)
    val values = SettleValues(worst = { busy[it] }, normal = { busy[it] })
    val gate = SettleGate(FakePrometheus { expr, _ -> if (expr == UPTIME) 600.0 else values.answer(expr) }) { now }
    assertThat(gate.status(state(150.0)).settled).isTrue()
  }

  @Test
  fun `the gate waits while values still fall, and gives up when they are stuck or after 20 minutes`() {
    val slow = normal + (LAG to 30.0)
    assertThat(gate(slow, past = normal + (LAG to 60.0)).status(state(400.0)).gaveUp).isFalse()
    assertThat(gate(slow).status(state(400.0)).gaveUp).isTrue()
    assertThat(gate(slow).status(state(100.0)).gaveUp).isFalse()
    assertThat(gate(slow, past = normal + (LAG to 60.0)).status(state(SettleGate.HARD_CAP_SECONDS + 1)).gaveUp).isTrue()
    assertThat(gate(slow).refusal(state(100.0)) is Refusal.LabSettling).isTrue()
    assertThat(gate(slow).refusal(state(400.0))).isNull()
  }

  @Test
  fun `a value Prometheus cannot give never holds the start`() {
    val status = SettleGate(FakePrometheus(up = false) { _, _ -> null }) { now }.status(state(150.0))
    assertThat(status.settled).isTrue()
  }

  @Test
  fun `the clean baseline starts after the last bad point, and never before the last end`() {
    val bad = listOf(Sample(now - 140, 0.9), Sample(now - 125, 0.1))
    val values = SettleValues(worst = { normal[it] }, normal = { normal[it] }, range = { if (it == P99) bad else emptyList() })
    val gate = SettleGate(FakePrometheus { expr, _ -> values.answer(expr) }.withRange(values)) { now }
    assertThat(gate.cleanSince(state(150.0), now)).isEqualTo(now - 140 + SettleGate.STEP_SECONDS)
    assertThat(gate().cleanSince(state(150.0), now)).isEqualTo(now - 150)
    assertThat(gate.cleanSince(state(null), now)).isNull()
  }
}

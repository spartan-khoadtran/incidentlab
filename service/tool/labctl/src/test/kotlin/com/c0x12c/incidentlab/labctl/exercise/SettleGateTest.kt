package com.c0x12c.incidentlab.labctl.exercise

import assertk.assertThat
import assertk.assertions.contains
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
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.UP_LATELY
import com.c0x12c.incidentlab.labctl.exercise.SettleGate.Companion.WAITING
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.NormalWindow
import org.junit.jupiter.api.Test

class SettleGateTest {

  private val now = 10_000.0
  private val normal = mapOf(LAG to 0.4, ERRORS to 0.0, P99 to 0.1, WAITING to 0.0, RSS to 400e6)

  private fun gate(
    worst: Map<String, Double> = normal,
    past: Map<String, Double>? = null,
    uptime: Double? = 600.0,
    upLately: Double? = 1.0,
    range: List<Sample> = emptyList(),
    clock: () -> Double = { now }
  ): SettleGate {
    val values = SettleValues(worst = { worst[it] }, normal = { normal[it] }, now = { worst[it] }, past = { (past ?: worst)[it] }, range = { range })
    val prometheus = FakePrometheus { expr, _ ->
      when (expr) {
        UPTIME -> uptime
        UP_LATELY -> upLately
        else -> values.answer(expr)
      }
    }
    return SettleGate(prometheus.withRange(values), clock)
  }

  private fun SettleGate.markNow(state: LabState): SettleMark = mark(state, now, status(state))

  private fun SettleGate.starts(state: LabState): Boolean = admit(state, SettleEntry.Wait) is Outcome.Done

  private fun state(endedAgo: Double?, startedAgo: Double? = 600.0) =
    LabState(lastEnded = endedAgo?.let { now - it }, normal = startedAgo?.let { NormalWindow(now - it - SettleGate.NORMAL_SECONDS, now - it) })

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
  fun `the gate waits while values still fall, and gives up when they are stuck`() {
    val slow = normal + (LAG to 30.0)
    assertThat(gate(slow, past = normal + (LAG to 60.0)).status(state(400.0)).gaveUp).isFalse()
    assertThat(gate(slow).status(state(400.0)).gaveUp).isTrue()
    assertThat(gate(slow).status(state(100.0)).gaveUp).isFalse()
    assertThat(gate(slow).starts(state(100.0))).isFalse()
    assertThat(gate(slow).starts(state(400.0))).isTrue()
  }

  @Test
  fun `the 20 minute cap counts from the first check, not from the end`() {
    for (endedAgo in listOf(3600.0, null)) {
      var clock = now
      val gate = gate(normal + (LAG to 30.0), past = normal + (LAG to 60.0), clock = { clock })
      val state = state(endedAgo)

      assertThat(gate.status(state).gaveUp).isFalse()
      while (clock <= now + SettleGate.HARD_CAP_SECONDS) {
        clock += 30
        gate.status(state)
      }
      assertThat(gate.status(state).gaveUp).isTrue()
    }
  }

  @Test
  fun `a give-up holds while the wait goes on, so the start after it is not refused`() {
    var clock = now
    var past = normal + (LAG to 30.0)
    val values = SettleValues(worst = { (normal + (LAG to 30.0))[it] }, normal = { normal[it] }, past = { past[it] })
    val gate = SettleGate(FakePrometheus { expr, _ -> if (expr == UPTIME) 600.0 else values.answer(expr) }) { clock }

    assertThat(gate.status(state(400.0)).gaveUp).isTrue()
    past = normal + (LAG to 60.0)
    assertThat(gate.starts(state(400.0))).isTrue()
    assertThat(gate.status(state(30.0)).gaveUp).isFalse()

    assertThat(gate.status(state(400.0)).gaveUp).isFalse()
    past = normal + (LAG to 30.0)
    assertThat(gate.status(state(400.0)).gaveUp).isTrue()
    clock += SettleGate.WAIT_GAP_SECONDS + 1
    past = normal + (LAG to 60.0)
    assertThat(gate.status(state(400.0)).gaveUp).isFalse()
  }

  @Test
  fun `a restart long after the last end waits for the uptime, it does not give up at once`() {
    val status = gate(uptime = 60.0).status(state(3600.0))

    assertThat(status.settled).isFalse()
    assertThat(status.gaveUp).isFalse()
  }

  @Test
  fun `a service with no answer for 2 minutes is stuck once the gate watched it for a minute`() {
    var clock = now
    val down = gate(uptime = 0.0, upLately = 0.0, clock = { clock })

    assertThat(down.status(state(400.0)).gaveUp).isFalse()
    clock += SettleGate.DOWN_GRACE_SECONDS / 2
    assertThat(down.status(state(400.0)).gaveUp).isFalse()
    clock += SettleGate.DOWN_GRACE_SECONDS / 2
    assertThat(down.status(state(400.0)).gaveUp).isTrue()
  }

  @Test
  fun `a service that is restarting is waited for, even long after the last end`() {
    var clock = now
    val restarting = gate(uptime = 0.0, upLately = 1.0, clock = { clock })

    repeat(4) {
      assertThat(restarting.status(state(3600.0)).gaveUp).isFalse()
      clock += SettleGate.DOWN_GRACE_SECONDS / 2
    }
  }

  @Test
  fun `a value that just went off is not stuck, so a new spike is waited out`() {
    val spike = gate(normal + (P99 to 0.9), past = normal).status(state(3600.0))

    assertThat(spike.settled).isFalse()
    assertThat(spike.gaveUp).isFalse()
    assertThat(gate(normal + (P99 to 0.9)).status(state(3600.0)).gaveUp).isTrue()
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

  @Test
  fun `normal is read over the clean window before the last settled start, not over the minutes before it`() {
    val prometheus = FakePrometheus { expr, _ -> if (expr == UPTIME) 600.0 else SettleValues(worst = { normal[it] }, normal = { normal[it] }).answer(expr) }
    SettleGate(prometheus) { now }.status(LabState(lastEnded = now - 150, normal = NormalWindow(now - 900, now - 700)))

    assertThat(prometheus.queries).contains("avg_over_time(($P99)[200s:15s] @ ${(now - 700).toLong()})")
  }

  @Test
  fun `a settled start moves normal to its clean stretch, at most 5 minutes long`() {
    val bad = listOf(Sample(now - 140, 0.9))
    val values = SettleValues(worst = { normal[it] }, normal = { normal[it] }, range = { if (it == P99) bad else emptyList() })
    val gate = SettleGate(FakePrometheus { expr, _ -> if (expr == UPTIME) 600.0 else values.answer(expr) }.withRange(values)) { now }

    assertThat(gate.markNow(state(150.0))).isEqualTo(SettleMark(now - 125, emptyList(), NormalWindow(now - 125, now)))
    assertThat(gate().markNow(state(900.0)).normal).isEqualTo(NormalWindow(now - SettleGate.NORMAL_SECONDS, now))
  }

  @Test
  fun `normal never reaches back before the load last changed`() {
    val steady = now - 200 + SettleGate.LOAD_STEADY_SECONDS

    assertThat(gate().markNow(state(900.0).copy(loadSince = now - 200)).normal).isEqualTo(NormalWindow(steady, now))
    assertThat(gate().markNow(state(900.0).copy(loadSince = now - 100)).normal).isNull()
  }

  @Test
  fun `a start that did not settle keeps the normal before, so a fault never becomes normal`() {
    val unsettled = gate(normal + (P99 to 2.5)).markNow(state(400.0))

    assertThat(unsettled.unsettled).isEqualTo(listOf("User p99"))
    assertThat(unsettled.normal).isNull()
    assertThat(gate().markNow(state(null)).normal).isNull()
  }
}

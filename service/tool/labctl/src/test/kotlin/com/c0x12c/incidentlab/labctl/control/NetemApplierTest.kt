package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class NetemApplierTest {

  private class MovingClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
  }

  private class RecordingExec(var fail: Boolean = false) : ContainerExec {
    val calls = mutableListOf<Pair<String, List<String>>>()

    override fun exec(service: String, command: List<String>): String {
      if (fail) {
        throw IllegalStateException("container not running")
      }
      calls += service to command
      return ""
    }
  }

  private val loss = Desired(faults = emptyMap(), docker = mapOf("api" to mapOf("cpus" to 2.0, "netem" to mapOf("target" to "pricing", "delay_ms" to 0, "loss_pct" to 2))))
  private val none = Desired(faults = emptyMap(), docker = mapOf("api" to mapOf("cpus" to 2.0)))

  @Test
  fun `a netem setting runs apply once and an unchanged tick runs nothing until the resync`() {
    val exec = RecordingExec()
    val clock = MovingClock(Instant.parse("2026-01-01T00:00:00Z"))
    val applier = NetemApplier(exec, clock, Duration.ofMinutes(1))

    applier.apply(loss)
    clock.now = clock.now.plusSeconds(3)
    applier.apply(loss)
    clock.now = clock.now.plusSeconds(56)
    applier.apply(loss)

    assertThat(exec.calls).containsExactly("api" to listOf("/run/netem.sh", "apply", "pricing", "0", "2"))

    clock.now = clock.now.plusSeconds(2)
    applier.apply(loss)
    assertThat(exec.calls).hasSize(2)
  }

  @Test
  fun `no netem setting runs clear, and a change runs at once`() {
    val exec = RecordingExec()
    val clock = MovingClock(Instant.parse("2026-01-01T00:00:00Z"))
    val applier = NetemApplier(exec, clock)

    applier.apply(none)
    clock.now = clock.now.plusSeconds(3)
    applier.apply(loss)
    clock.now = clock.now.plusSeconds(3)
    applier.apply(none)

    assertThat(exec.calls.map { it.second }).containsExactly(
      listOf("/run/netem.sh", "clear"),
      listOf("/run/netem.sh", "apply", "pricing", "0", "2"),
      listOf("/run/netem.sh", "clear")
    )
  }

  @Test
  fun `a failed exec is tried again next tick`() {
    val exec = RecordingExec(fail = true)
    val clock = MovingClock(Instant.parse("2026-01-01T00:00:00Z"))
    val applier = NetemApplier(exec, clock)

    applier.apply(loss)
    exec.fail = false
    clock.now = clock.now.plusSeconds(3)
    applier.apply(loss)
    clock.now = clock.now.plusSeconds(3)
    applier.apply(loss)

    assertThat(exec.calls).hasSize(1)
  }

  @Test
  fun `several targets become one apply with one triple each, and numbers print plainly`() {
    val netem = listOf(
      mapOf("target" to "pricing", "delay_ms" to 5.0, "loss_pct" to 0),
      mapOf("target" to "postgres", "delay_ms" to 2.5, "loss_pct" to 0.5)
    )

    assertThat(NetemApplier.command(netem)).isEqualTo(listOf("/run/netem.sh", "apply", "pricing", "5", "0", "postgres", "2.5", "0.5"))
    assertThat(NetemApplier.command(null)).isEqualTo(listOf("/run/netem.sh", "clear"))
    assertThat(NetemApplier.command(emptyList<Any>())).isEqualTo(listOf("/run/netem.sh", "clear"))
  }

  @Test
  fun `only containers with NET_ADMIN are touched`() {
    val exec = RecordingExec()
    val worker = Desired(faults = emptyMap(), docker = mapOf("api" to mapOf("cpus" to 2.0), "worker" to mapOf("netem" to mapOf("target" to "kafka", "delay_ms" to 5, "loss_pct" to 0))))

    NetemApplier(exec).apply(worker)

    assertThat(exec.calls).containsExactly("api" to listOf("/run/netem.sh", "clear"))
  }
}

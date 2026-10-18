package com.c0x12c.incidentlab.noisy.storm

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.noisy.factory.NoisyFaults
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class ForkStormTest {

  @Test
  fun `the storm starts and reaps its share of the second on every tick`() {
    val faults = Faults(NoisyFaults.DEFAULTS).also { it.replace(mapOf(NoisyFaults.FORK_RPS to 50)) }
    val storm = ForkStorm(faults, SimpleMeterRegistry())

    repeat(TICKS_PER_SECOND) { storm.tick() }

    assertThat(storm.started).isEqualTo(50L)
  }

  @Test
  fun `a rate that does not divide into ticks is carried over, and the cap holds`() {
    val faults = Faults(NoisyFaults.DEFAULTS).also { it.replace(mapOf(NoisyFaults.FORK_RPS to 15)) }
    val storm = ForkStorm(faults, SimpleMeterRegistry())
    repeat(TICKS_PER_SECOND) { storm.tick() }
    assertThat(storm.started).isEqualTo(15L)

    val capped = ForkStorm(Faults(NoisyFaults.DEFAULTS).also { it.replace(mapOf(NoisyFaults.FORK_RPS to 5_000)) }, SimpleMeterRegistry())
    capped.tick()
    assertThat(capped.started).isEqualTo(20L)
  }

  @Test
  fun `the healthy default starts nothing`() {
    val storm = ForkStorm(Faults(NoisyFaults.DEFAULTS), SimpleMeterRegistry())
    repeat(TICKS_PER_SECOND) { storm.tick() }
    assertThat(storm.started).isEqualTo(0L)
  }

  private companion object {
    const val TICKS_PER_SECOND = 10
  }
}

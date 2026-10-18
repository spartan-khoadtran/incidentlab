package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class CpuNumbersTest {

  @Test
  fun `weights split a contended CPU in proportion`() {
    assertThat(CpuNumbers.weightSharePercent(300, 100)).isEqualTo(75.0)
    assertThat(CpuNumbers.weightSharePercent(3_000, 200)).isEqualTo(93.75)
  }

  @Test
  fun `credits match the published burstable table`() {
    // t3.nano: 2 vCPUs at 5% earn 6 credits an hour; t3.small: 2 vCPUs at 20% earn 24.
    assertThat(CpuNumbers.creditsPerHour(2, 5)).isCloseTo(6.0, EPSILON)
    assertThat(CpuNumbers.creditsPerHour(2, 20)).isCloseTo(24.0, EPSILON)
    // 576 credits, net spend 2 × (1.0 − 0.2) = 1.6 a minute: 360 minutes.
    assertThat(CpuNumbers.minutesUntilCreditsGone(576, 2, 100, 20)).isCloseTo(360.0, EPSILON)
  }

  @Test
  fun `a lock follows the utilization law and M_M_1`() {
    assertThat(CpuNumbers.lockUtilizationPercent(15, 60)).isCloseTo(90.0, EPSILON)
    assertThat(CpuNumbers.lockWaitMs(90, 60)).isCloseTo(540.0, EPSILON)
    assertThat(CpuNumbers.lockWaitMs(75, 50)).isCloseTo(150.0, EPSILON)
  }

  @Test
  fun `a fork storm costs its rate times the CPU per fork`() {
    assertThat(CpuNumbers.forkCores(200, 1.5)).isCloseTo(0.3, EPSILON)
  }

  private companion object {
    const val EPSILON = 1e-9
  }
}

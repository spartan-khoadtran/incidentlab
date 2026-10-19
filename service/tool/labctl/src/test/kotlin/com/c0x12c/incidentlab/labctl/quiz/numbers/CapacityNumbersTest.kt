package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isCloseTo
import org.junit.jupiter.api.Test

/** The capacity math on numbers worked by hand. */
class CapacityNumbersTest {

  @Test
  fun `the pool of the lab is 19 percent busy at 50 rps and full at about 267 rps`() {
    // 50 × 0.3 × 0.05 s = 0.75 connections, over 4 = 18.75%
    assertThat(CapacityNumbers.poolUtilization(50.0, 0.3, 50.0, 4)).isCloseTo(18.75, 1e-9)
    // 4 / (0.3 × 0.05 s) = 4 / 0.015 = 266.67
    assertThat(CapacityNumbers.poolMaxRate(4, 0.3, 50.0)).isCloseTo(266.67, 0.01)
  }

  @Test
  fun `headroom as a free share and as growth`() {
    // 1 − 150 / 200 = 0.25, and 200 / 150 − 1 = 0.3333
    assertThat(CapacityNumbers.freeShare(150.0, 200.0)).isCloseTo(25.0, 1e-9)
    assertThat(CapacityNumbers.growth(150.0, 200.0)).isCloseTo(33.33, 0.01)
  }

  @Test
  fun `closed clients follow N = X times R`() {
    // 20 / 0.075 s = 266.67 rps, and 20 / 267 = 0.0749 s
    assertThat(CapacityNumbers.closedRate(20, 75.0)).isCloseTo(266.67, 0.01)
    assertThat(CapacityNumbers.closedTimeMs(20, 267.0)).isCloseTo(74.91, 0.01)
  }

  @Test
  fun `a 2 s stall leaves 20 slow samples out of about 11,620`() {
    // 20 × 58 s / 0.1 s = 11,600 normal samples, plus 20 slow: 20 / 11,620 = 0.172%
    assertThat(CapacityNumbers.closedSlowShare(20, 100.0, 60, 2)).isCloseTo(0.1721, 0.0001)
  }
}

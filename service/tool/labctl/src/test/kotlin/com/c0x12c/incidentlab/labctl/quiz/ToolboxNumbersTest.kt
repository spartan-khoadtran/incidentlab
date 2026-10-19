package com.c0x12c.incidentlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.c0x12c.incidentlab.labctl.quiz.numbers.ToolboxNumbers
import org.junit.jupiter.api.Test

class ToolboxNumbersTest {

  @Test
  fun `cpu max is the quota over the period`() {
    assertThat(ToolboxNumbers.cores(8_000, 100_000)).isCloseTo(0.08, 1e-9)
    assertThat(ToolboxNumbers.cores(150_000, 50_000)).isCloseTo(3.0, 1e-9)
  }

  @Test
  fun `the throttled share uses the changes between two readings`() {
    assertThat(ToolboxNumbers.throttledShare(50_000, 51_000, 2_000, 2_300)).isCloseTo(30.0, 1e-9)
  }

  @Test
  fun `queries per request use the changes between two readings`() {
    assertThat(ToolboxNumbers.perRequest(1_000_000, 1_003_000, 400_000, 400_150)).isCloseTo(20.0, 1e-9)
  }
}

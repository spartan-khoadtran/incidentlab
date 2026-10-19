package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/** The closed forms, checked against values worked out by hand in docs/foundations.md. */
class FormulasTest {

  @Test
  fun `harmonic mean is total work over total time, and the geometric mean cancels a loss against a gain`() {
    // 3 runs of 1000 requests at 100, 200, 400 req/s take 10 + 5 + 2.5 = 17.5 s: 3000 / 17.5
    assertThat(Formulas.harmonicMean(listOf(100.0, 200.0, 400.0))).isCloseTo(3000 / 17.5, 1e-9)
    assertThat(Formulas.geometricMean(listOf(2.0, 8.0))).isCloseTo(4.0, 1e-9)
    assertThat(Formulas.geometricMean(listOf(0.5, 2.0))).isCloseTo(1.0, 1e-9)
  }

  @Test
  fun `CV is the deviation over the mean, and a cache mix has its mean between the humps`() {
    assertThat(Formulas.coefficientOfVariation(100.0, 150.0)).isCloseTo(1.5, 1e-12)
    assertThat(Formulas.coefficientOfVariation(10.0, 1.6)).isCloseTo(0.16, 1e-12)
    // 80% hits at 2 ms and 20% misses at 40 ms: 1.6 + 8 = 9.6 ms
    assertThat(Formulas.meanOfTwoModes(2.0, 40.0, 20.0)).isCloseTo(9.6, 1e-12)
    assertThat(Formulas.meanOfTwoModes(10.0, 200.0, 5.0)).isCloseTo(19.5, 1e-12)
  }

  @Test
  fun `one cycle of 0,3 ns stretched to 1 s makes a memory read about 6 minutes and a disk read about a year`() {
    assertThat(Formulas.scaledToOneSecondCycle(100e-9, 0.3e-9) / 60).isCloseTo(5.556, 1e-3)
    assertThat(Formulas.scaledToOneSecondCycle(100e-6, 0.3e-9) / 86_400).isCloseTo(3.858, 1e-3)
    assertThat(Formulas.scaledToOneSecondCycle(10e-3, 0.3e-9) / (365 * 86_400.0)).isCloseTo(1.057, 1e-3)
  }

  @Test
  fun `p99 of a two-mode mix is the slow mode only when more than 1 percent is slow`() {
    assertThat(Formulas.quantileOfTwoModes(10.0, 200.0, 0.5, 99.0)).isEqualTo(10.0)
    assertThat(Formulas.quantileOfTwoModes(10.0, 200.0, 2.0, 99.0)).isEqualTo(200.0)
    assertThat(Formulas.quantileOfTwoModes(10.0, 200.0, 0.5, 99.9)).isEqualTo(200.0)
  }

  @Test
  fun `Amdahl gives 4,7x for p 0,9 and 8 workers, and caps at 1 over the serial share`() {
    assertThat(Formulas.amdahl(0.9, 8)).isCloseTo(1 / 0.2125, 1e-9)
    assertThat(Formulas.amdahl(0.9, 1_000_000)).isCloseTo(10.0, 1e-4)
    assertThat(Formulas.amdahl(0.5, 4)).isCloseTo(1.6, 1e-9)
  }

  @Test
  fun `USL with sigma 0,05 and kappa 0,001 gives 8,04 at 16 workers and peaks near 31`() {
    assertThat(Formulas.usl(0.05, 0.001, 16)).isCloseTo(16 / 1.99, 1e-9)
    assertThat(Formulas.usl(0.05, 0.001, 1)).isEqualTo(1.0)
    assertThat(Formulas.uslPeak(0.05, 0.001)).isCloseTo(30.822, 1e-3)
    assertThat(Formulas.usl(0.05, 0.001, 31) > Formulas.usl(0.05, 0.001, 64)).isEqualTo(true)
    // with no coherency cost USL is Amdahl with sigma = 1 - p
    assertThat(Formulas.usl(0.1, 0.0, 8)).isCloseTo(Formulas.amdahl(0.9, 8), 1e-9)
  }

  @Test
  fun `the queue waits at rho 0,8 and S 10 ms are 40 and 17,8 ms`() {
    assertThat(Formulas.mm1Wait(0.8, 10.0)).isCloseTo(40.0, 1e-9)
    assertThat(Formulas.mm2Wait(0.8, 10.0)).isCloseTo(0.64 / (100 * 0.36) * 1000, 1e-9)
    assertThat(Formulas.sharedQueueGain(0.8)).isCloseTo(2.25, 1e-9)
  }

  @Test
  fun `M-G-1 scales the M-M-1 wait by (1 + CV squared) over 2, and a closed system caps at users over response time`() {
    assertThat(Formulas.mg1Wait(0.8, 10.0, 1.0)).isCloseTo(Formulas.mm1Wait(0.8, 10.0), 1e-12)
    assertThat(Formulas.mg1Wait(0.8, 10.0, 0.0)).isCloseTo(Formulas.mm1Wait(0.8, 10.0) / 2, 1e-12)
    assertThat(Formulas.mg1Wait(0.8, 10.0, 2.0)).isCloseTo(100.0, 1e-9)
    assertThat(Formulas.closedThroughput(20, 0.05)).isCloseTo(400.0, 1e-9)
  }

  @Test
  fun `the M-M-2 wait matches Erlang C for two servers`() {
    for (rho in listOf(0.3, 0.5, 0.8, 0.95)) {
      val service = 0.01
      val mu = 1 / service
      val lambda = 2 * rho * mu
      val a = lambda / mu
      val p0 = 1 / (1 + a + a * a / (2 * (1 - rho)))
      val erlangC = p0 * a * a / (2 * (1 - rho))
      assertThat(Formulas.mm2Wait(rho, service)).isCloseTo(erlangC / (2 * mu - lambda), 1e-12)
      assertThat(Formulas.mm1Wait(rho, service) / Formulas.mm2Wait(rho, service)).isCloseTo(Formulas.sharedQueueGain(rho), 1e-9)
    }
  }
}

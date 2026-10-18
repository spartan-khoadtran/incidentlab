package com.c0x12c.incidentlab.labctl.ramp

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class RampMathTest {

  private fun step(index: Int, achieved: Double?, p99: Double?, errors: Double?) =
    StepReading(index, RampPlan.rps(index), RampPlan.rps(index).toDouble(), achieved, p99, errors)

  /** A pool of 3: flat up to 150 rps, p99 bends at 200, errors at 250. */
  private val pool = listOf(
    step(0, 50.0, 0.080, 0.0),
    step(1, 75.0, 0.085, 0.0),
    step(2, 100.0, 0.090, 0.0),
    step(3, 125.0, 0.110, 0.0),
    step(4, 149.0, 0.180, 0.0),
    step(5, 196.0, 0.420, 0.002),
    step(6, 203.0, 1.900, 0.060),
    step(7, 201.0, 2.000, 0.250)
  )

  @Test
  fun `the knee is the first step above three times the first p99`() {
    assertThat(RampMath.knee(pool)).isEqualTo(5)
    assertThat(RampMath.maxThroughput(pool)).isEqualTo(203.0)
  }

  @Test
  fun `errors above one percent make a knee even when p99 is flat`() {
    val failing = listOf(step(0, 50.0, 0.08, 0.0), step(1, 74.0, 0.09, 0.005), step(2, 90.0, 0.09, 0.03))
    assertThat(RampMath.knee(failing)).isEqualTo(2)
  }

  @Test
  fun `a flat ramp has no knee, and a missing first p99 cannot make one`() {
    val flat = pool.map { it.copy(p99Seconds = 0.08, errorShare = 0.0, achieved = it.targetRps.toDouble()) }
    assertThat(RampMath.knee(flat)).isNull()
    assertThat(RampMath.knee(listOf(step(0, 50.0, null, 0.0), step(1, 75.0, 5.0, 0.0)))).isNull()
  }

  @Test
  fun `a typed rate maps to the nearest step`() {
    assertThat(RampMath.nearestStep(190.0, pool)).isEqualTo(5)
    assertThat(RampMath.nearestStep(140.0, pool)).isEqualTo(4)
    assertThat(RampMath.nearestStep(1_000.0, pool)).isEqualTo(7)
  }

  @Test
  fun `a full answer scores five`() {
    val verdict = RampMath.verdict(RampAnswer(200.0, 200.0, RampCatalog.POOL), pool, RampCatalog.POOL)

    assertThat(verdict.points).isEqualTo(5.0)
    assertThat(verdict.missed).containsExactly()
  }

  @Test
  fun `the knee counts within one step, the maximum within ten percent`() {
    assertThat(RampMath.verdict(RampAnswer(150.0, 183.0, RampCatalog.POOL), pool, RampCatalog.POOL).points).isEqualTo(5.0)
    assertThat(RampMath.verdict(RampAnswer(250.0, 224.0, RampCatalog.POOL), pool, RampCatalog.POOL).points).isEqualTo(3.0)

    val wrong = RampMath.verdict(RampAnswer(125.0, 182.0, RampCatalog.CPU), pool, RampCatalog.POOL)
    assertThat(wrong.points).isEqualTo(0.0)
    assertThat(wrong.missed).containsExactly("knee", "max throughput", "resource")
  }

  @Test
  fun `with no measured knee, zero and none are right`() {
    val flat = pool.map { it.copy(p99Seconds = 0.08, errorShare = 0.0, achieved = it.targetRps.toDouble()) }

    val none = RampMath.verdict(RampAnswer(0.0, 300.0, RampCatalog.NONE), flat, RampCatalog.NONE)
    assertThat(none.kneeCorrect).isTrue()
    assertThat(none.points).isEqualTo(5.0)
    assertThat(RampMath.verdict(RampAnswer(300.0, 300.0, RampCatalog.NONE), flat, RampCatalog.NONE).kneeCorrect).isFalse()
    assertThat(RampMath.verdict(RampAnswer(0.0, 300.0, RampCatalog.NONE), flat, RampCatalog.CPU).resourceCorrect).isTrue()
    assertThat(RampMath.verdict(RampAnswer(0.0, 300.0, RampCatalog.NONE), pool, RampCatalog.POOL).kneeCorrect).isFalse()
  }

  @Test
  fun `no achieved sample means no maximum and no points for it`() {
    val empty = listOf(step(0, null, 0.08, 0.0))
    assertThat(RampMath.verdict(RampAnswer(0.0, 0.0, RampCatalog.NONE), empty, RampCatalog.NONE).maxCorrect).isFalse()
  }
}

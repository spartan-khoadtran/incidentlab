package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.scenario.Baseline
import kotlin.math.floor
import kotlin.math.roundToInt

/** The load schedule of `make ramp`: fixed multipliers of the base load, each held for the same time. */
object RampPlan {

  val MULTIPLIERS: List<Double> = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0)

  const val DEFAULT_STEP_SECONDS = 60
  const val MIN_STEP_SECONDS = 30
  const val MAX_STEP_SECONDS = 300

  /** The first seconds of a step stay out of its window, so the step before does not leak into it. */
  const val SETTLE_SECONDS = 10

  val size: Int = MULTIPLIERS.size

  val rates: List<Int> = MULTIPLIERS.indices.map(::rps)

  fun rps(index: Int): Int = (Baseline.BASE_RPS * MULTIPLIERS[index]).roundToInt()

  /** The step running at this time, or null once the ramp is over. */
  fun stepAt(elapsedSeconds: Double, every: Int): Int? = floor(elapsedSeconds / every).toInt().takeIf { it in MULTIPLIERS.indices }

  /** How many steps have run to their end. */
  fun stepsRun(elapsedSeconds: Double, every: Int): Int = floor(elapsedSeconds / every).toInt().coerceIn(0, size)

  fun totalSeconds(every: Int): Int = size * every

  fun windowSeconds(every: Int): Int = every - SETTLE_SECONDS
}

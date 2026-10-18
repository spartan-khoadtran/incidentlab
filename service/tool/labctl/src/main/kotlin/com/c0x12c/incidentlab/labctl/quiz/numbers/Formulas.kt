package com.c0x12c.incidentlab.labctl.quiz.numbers

import kotlin.math.pow
import kotlin.math.sqrt

/** The closed forms behind the foundations drills, kept apart so a test can check them with known values. */
internal object Formulas {

  fun harmonicMean(values: List<Double>): Double = values.size / values.sumOf { 1 / it }

  fun geometricMean(values: List<Double>): Double = values.fold(1.0) { acc, v -> acc * v }.pow(1.0 / values.size)

  fun coefficientOfVariation(mean: Double, standardDeviation: Double): Double = standardDeviation / mean

  /** The mean of a mix: [slowShare] percent of requests at [slow], the rest at [fast]. */
  fun meanOfTwoModes(fast: Double, slow: Double, slowShare: Double): Double = (fast * (PERCENT - slowShare) + slow * slowShare) / PERCENT

  /** How long [seconds] lasts when one cycle of [cycleSeconds] is stretched to one second. */
  fun scaledToOneSecondCycle(seconds: Double, cycleSeconds: Double): Double = seconds / cycleSeconds

  /** The percentile [quantile] of a mix: [slowShare] percent of requests at [slow], the rest at [fast]. */
  fun quantileOfTwoModes(fast: Double, slow: Double, slowShare: Double, quantile: Double): Double =
    if (slowShare > PERCENT - quantile) slow else fast

  /** Amdahl: the speedup with [workers] when the share [parallel] of the work runs in parallel. */
  fun amdahl(parallel: Double, workers: Int): Double = 1 / ((1 - parallel) + parallel / workers)

  /** Universal Scalability Law: throughput with [workers] relative to one, [sigma] contention and [kappa] coherency. */
  fun usl(sigma: Double, kappa: Double, workers: Int): Double = workers / (1 + sigma * (workers - 1) + kappa * workers * (workers - 1))

  fun uslPeak(sigma: Double, kappa: Double): Double = sqrt((1 - sigma) / kappa)

  /** Mean wait in queue of M/M/1, in the unit of [service]. */
  fun mm1Wait(rho: Double, service: Double): Double = rho / (1 - rho) * service

  /** Mean wait in queue of M/D/1: half of M/M/1, because the service time has no variance. */
  fun md1Wait(rho: Double, service: Double): Double = rho / (2 * (1 - rho)) * service

  /** Mean wait in queue of M/G/1: the M/M/1 wait times (1 + CV²) / 2, where [cv] is the service time's coefficient of variation. */
  fun mg1Wait(rho: Double, service: Double, cv: Double): Double = mm1Wait(rho, service) * (1 + cv * cv) / 2

  /** Throughput of a closed system with [users] and no think time: Little's Law with L = users. */
  fun closedThroughput(users: Int, responseSeconds: Double): Double = users / responseSeconds

  /** Mean wait in queue of M/M/2 with one shared queue, rho = lambda / (2 mu) and mu = 1 / [service]. */
  fun mm2Wait(rho: Double, service: Double): Double = rho * rho * service / (1 - rho * rho)

  /** The share of requests that wait at all in M/M/2. */
  fun mm2WaitProbability(rho: Double): Double = 2 * rho * rho / (1 + rho)

  private const val PERCENT = 100.0
}

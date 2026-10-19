package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** Numeric drills for docs/capacity.md. The math sits in small functions so a test can check it on fixed numbers. */
internal object CapacityNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::poolUtilizationDrill, ::poolMaxRateDrill, ::freeShareDrill, ::growthDrill, ::nextSaturationDrill, ::kneeThresholdDrill,
    ::closedRateDrill, ::closedTimeDrill, ::stallArrivalsDrill, ::closedSlowShareDrill
  )

  /** U = X × S / c, in percent, for a pool that only `share` of the requests use for `holdMs`. */
  fun poolUtilization(rps: Double, share: Double, holdMs: Double, pool: Int): Double = rps * share * holdMs / MILLIS_PER_SECOND / pool * PERCENT

  /** X max = c / D, with D = share × hold. */
  fun poolMaxRate(pool: Int, share: Double, holdMs: Double): Double = pool / (share * holdMs / MILLIS_PER_SECOND)

  fun freeShare(peak: Double, capacity: Double): Double = (1 - peak / capacity) * PERCENT

  fun growth(peak: Double, capacity: Double): Double = (capacity / peak - 1) * PERCENT

  /** N = X × R with no think time, so X = N / R. */
  fun closedRate(clients: Int, responseMs: Double): Double = clients / (responseMs / MILLIS_PER_SECOND)

  fun closedTimeMs(clients: Int, rps: Double): Double = clients / rps * MILLIS_PER_SECOND

  /** Each closed client records one slow sample per stall; the rest of the run it records at its normal pace. */
  fun closedSlowShare(clients: Int, responseMs: Double, runSeconds: Int, stallSeconds: Int): Double {
    val normal = clients * (runSeconds - stallSeconds) / (responseMs / MILLIS_PER_SECOND)
    return clients / (normal + clients) * PERCENT
  }

  private fun poolUtilizationDrill(r: Random): NumericQuestion {
    val rps = listOf(60, 100, 150, 200).random(r)
    val share = listOf(0.2, 0.3, 0.5).random(r)
    val hold = listOf(20, 40, 50).random(r)
    val pool = listOf(4, 8, 10).filter { poolUtilization(rps.toDouble(), share, hold.toDouble(), it) < MAX_DRAWN_UTILIZATION }.random(r)
    val answer = poolUtilization(rps.toDouble(), share, hold.toDouble(), pool)
    return NumericQuestion(
      "A service gets $rps requests/s. ${shown(share * PERCENT)}% of them hold a DB connection for $hold ms, the rest use none. The pool has $pool connections. What is the pool's utilization in %?",
      answer, Tolerance.Relative(0.03), "%",
      "Demand per request D = ${shown(share)} × ${shown(hold / MILLIS_PER_SECOND)} s = ${shown(share * hold / MILLIS_PER_SECOND)} s. " +
        "U = X × D / c = $rps × ${shown(share * hold / MILLIS_PER_SECOND)} / $pool = ${shown(answer)}%.",
      "Capacity from a ramp test > Utilization Law"
    )
  }

  private fun poolMaxRateDrill(r: Random): NumericQuestion {
    val pool = listOf(2, 3, 4, 6, 10).random(r)
    val share = listOf(0.2, 0.25, 0.5).random(r)
    val hold = listOf(40, 50, 80).random(r)
    val answer = poolMaxRate(pool, share, hold.toDouble())
    return NumericQuestion(
      "${shown(share * PERCENT)}% of requests hold one of $pool pooled connections for $hold ms. At which total request rate is the pool 100% busy?",
      answer, Tolerance.Relative(0.03), "requests/s",
      "D = ${shown(share)} × ${shown(hold / MILLIS_PER_SECOND)} s = ${shown(share * hold / MILLIS_PER_SECOND)} s per request. " +
        "U = X × D / c reaches 1 at X = c / D = $pool / ${shown(share * hold / MILLIS_PER_SECOND)} = ${shown(answer)} requests/s.",
      "Capacity from a ramp test > Utilization Law"
    )
  }

  private fun freeShareDrill(r: Random): NumericQuestion {
    val knee = listOf(400, 800, 1_200).random(r)
    val peak = (knee * listOf(0.5, 0.6, 0.75, 0.8).random(r)).toInt()
    val answer = freeShare(peak.toDouble(), knee.toDouble())
    return NumericQuestion(
      "A ramp test puts the knee at $knee requests/s. The busiest hour of the week peaks at $peak requests/s. What share of the capacity is still free, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "1 − peak / capacity = 1 − $peak / $knee = ${shown(answer)}%. Use the knee as the capacity, not the maximum.",
      "Capacity from a ramp test > Headroom and the next saturation"
    )
  }

  private fun growthDrill(r: Random): NumericQuestion {
    val knee = listOf(300, 600, 900).random(r)
    val peak = (knee * listOf(0.5, 0.6, 0.75).random(r)).toInt()
    val answer = growth(peak.toDouble(), knee.toDouble())
    return NumericQuestion(
      "The knee is at $knee requests/s and today's peak is $peak requests/s. By how many % can the peak traffic still grow before it reaches the knee?",
      answer, Tolerance.Absolute(0.5), "%",
      "capacity / peak − 1 = $knee / $peak − 1 = ${shown(answer)}%. It is larger than the free share (${shown(freeShare(peak.toDouble(), knee.toDouble()))}%), so say which one you mean.",
      "Capacity from a ramp test > Headroom and the next saturation"
    )
  }

  private fun nextSaturationDrill(r: Random): NumericQuestion {
    val share = listOf(0.2, 0.3, 0.4).random(r)
    val consumer = listOf(60, 113, 150).random(r)
    val answer = consumer / share
    return NumericQuestion(
      "The pool was the bottleneck and was made larger. Each order request (${shown(share * PERCENT)}% of all requests) produces one Kafka message, and the consumer handles $consumer messages/s at most. At which total request rate does the consumer saturate?",
      answer, Tolerance.Relative(0.03), "requests/s",
      "X max = capacity / demand = $consumer / ${shown(share)} = ${shown(answer)} requests/s. Past it, lag grows while api latency stays flat.",
      "Capacity from a ramp test > Headroom and the next saturation"
    )
  }

  private fun kneeThresholdDrill(r: Random): NumericQuestion {
    val base = listOf(60, 80, 95, 120).random(r)
    val answer = (base * KNEE_FACTOR).toDouble()
    return NumericQuestion(
      "In `make ramp`, step 1's p99 is $base ms. Above which p99 (in ms) does a later step count as the knee, if its errors stay under 1%?",
      answer, Tolerance.Relative(0.01), "ms",
      "The knee is the first step with p99 above 3 times step 1's: 3 × $base = ${shown(answer)} ms, or with errors above 1%.",
      "Capacity from a ramp test > The ramp in this lab"
    )
  }

  private fun closedRateDrill(r: Random): NumericQuestion {
    val clients = listOf(10, 20, 50).random(r)
    val response = listOf(25, 40, 75, 100).random(r)
    val answer = closedRate(clients, response.toDouble())
    return NumericQuestion(
      "A benchmark runs $clients closed-loop clients with no think time. The mean response time is $response ms. How many requests/s does it send?",
      answer, Tolerance.Relative(0.03), "requests/s",
      "Little's Law with no think time: N = X × R, so X = $clients / ${shown(response / MILLIS_PER_SECOND)} s = ${shown(answer)} requests/s.",
      "Closed-loop clients in this lab > What make bench does"
    )
  }

  private fun closedTimeDrill(r: Random): NumericQuestion {
    val clients = listOf(20, 30, 40).random(r)
    val rps = listOf(200, 267, 400).random(r)
    val answer = closedTimeMs(clients, rps.toDouble())
    return NumericQuestion(
      "`make bench` runs $clients clients and the rate settles at $rps requests/s. What mean response time in ms do the clients see?",
      answer, Tolerance.Relative(0.03), "ms",
      "R = N / X = $clients / $rps = ${shown(answer / MILLIS_PER_SECOND)} s = ${shown(answer)} ms. N = X × R holds for any closed loop with no think time.",
      "Closed-loop clients in this lab > What make bench does"
    )
  }

  private fun stallArrivalsDrill(r: Random): NumericQuestion {
    val rps = listOf(100, 200, 500).random(r)
    val stall = listOf(1, 2, 3).random(r)
    val answer = (rps * stall).toDouble()
    return NumericQuestion(
      "Users arrive at $rps requests/s, independent of each other. The service stalls for $stall s. How many user requests arrive during the stall?",
      answer, Tolerance.Relative(0.01), "requests",
      "$rps × $stall = ${shown(answer)}. Each one meets the stall. A closed-loop tool with N clients records only N slow samples, one per client.",
      "Closed-loop clients in this lab > Why the two p99 values differ"
    )
  }

  private fun closedSlowShareDrill(r: Random): NumericQuestion {
    val clients = listOf(10, 20).random(r)
    val response = listOf(50, 100).random(r)
    val run = listOf(60, 120).random(r)
    val stall = listOf(1, 2).random(r)
    val answer = closedSlowShare(clients, response.toDouble(), run, stall)
    val normal = clients * (run - stall) / (response / MILLIS_PER_SECOND)
    return NumericQuestion(
      "$clients closed-loop clients run for $run s at $response ms per request. The service stalls once for $stall s, and each client records one slow request. What share of the samples is slow, in %?",
      answer, Tolerance.Relative(0.05), "%",
      "Normal samples: $clients × ${run - stall} s / ${shown(response / MILLIS_PER_SECOND)} s = ${shown(normal)}. Slow: $clients. " +
        "$clients / ${shown(normal + clients)} = ${shown(answer)}%, under 1%, so p99 shows no stall at all.",
      "Closed-loop clients in this lab > Why the two p99 values differ"
    )
  }

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val KNEE_FACTOR = 3
  private const val MAX_DRAWN_UTILIZATION = 95.0
}

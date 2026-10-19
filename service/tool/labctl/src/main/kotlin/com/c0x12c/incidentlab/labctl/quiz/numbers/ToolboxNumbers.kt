package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** Numeric drills for docs/toolbox.md: the numbers a shell gives you, read the way the files and counters need. */
internal object ToolboxNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(::cpuMaxCores, ::throttledShareDrill, ::perRequestDrill)

  fun cores(quotaMicros: Int, periodMicros: Int): Double = quotaMicros.toDouble() / periodMicros

  fun throttledShare(periodsBefore: Long, periodsAfter: Long, throttledBefore: Long, throttledAfter: Long): Double =
    (throttledAfter - throttledBefore).toDouble() / (periodsAfter - periodsBefore) * PERCENT

  fun perRequest(queriesBefore: Long, queriesAfter: Long, requestsBefore: Long, requestsAfter: Long): Double =
    (queriesAfter - queriesBefore).toDouble() / (requestsAfter - requestsBefore)

  private fun cpuMaxCores(r: Random): NumericQuestion {
    val period = listOf(100_000, 50_000).random(r)
    val quota = listOf(5_000, 8_000, 25_000, 50_000, 150_000, 200_000).random(r)
    val answer = cores(quota, period)
    return NumericQuestion(
      "A container's cpu.max reads `$quota $period`. How many cores may it use?",
      answer, Tolerance.Relative(0.01), "cores",
      "cpu.max is the quota and the period in microseconds: $quota / $period = ${shown(answer)} cores. The container may run $quota µs of CPU in every $period µs window, then it waits.",
      "The 60-second checklist > From host to container (cpu.max)"
    )
  }

  private fun throttledShareDrill(r: Random): NumericQuestion {
    val periodsBefore = r.nextLong(10_000, 90_000)
    val seconds = listOf(10, 20, 30).random(r)
    val periods = seconds * PERIODS_PER_SECOND
    val throttled = listOf(5, 15, 30, 60).random(r) * periods / WHOLE_PERCENT
    val throttledBefore = r.nextLong(0, periodsBefore / 2)
    val answer = throttledShare(periodsBefore, periodsBefore + periods, throttledBefore, throttledBefore + throttled)
    return NumericQuestion(
      "You read a container's cpu.stat twice, $seconds s apart. nr_periods goes from $periodsBefore to ${periodsBefore + periods}, and nr_throttled from $throttledBefore to ${throttledBefore + throttled}. What share of the periods was throttled in that time, in %?",
      answer, Tolerance.Relative(0.02), "%",
      "Only the changes count: $throttled throttled periods out of $periods periods = ${shown(answer)}%. The totals since the start ($throttledBefore / $periodsBefore) mix in the hours before the problem.",
      "The 60-second checklist > From host to container (cpu.stat)"
    )
  }

  private fun perRequestDrill(r: Random): NumericQuestion {
    val seconds = 10
    val checkoutRate = listOf(12, 15, 18).random(r)
    val perCheckout = listOf(10, 15, 20, 25).random(r)
    val requestsBefore = r.nextLong(100_000, 900_000)
    val queriesBefore = requestsBefore * 3 + r.nextLong(0, 50_000)
    val requests = checkoutRate * seconds.toLong()
    val queries = requests * perCheckout
    val answer = perRequest(queriesBefore, queriesBefore + queries, requestsBefore, requestsBefore + requests)
    return NumericQuestion(
      "Two curls of api's /metrics, $seconds s apart. db_client_queries_total for product_by_id goes from $queriesBefore to ${queriesBefore + queries}. http_server_requests_total for /checkout goes from $requestsBefore to ${requestsBefore + requests}. How many product_by_id queries does one checkout send now?",
      answer, Tolerance.Relative(0.02), "queries",
      "Divide the changes, not the totals: $queries queries / $requests checkouts = ${shown(answer)} per checkout. The totals ($queriesBefore / $requestsBefore) include the time before the change, like a rate() over the whole uptime.",
      "The toolbox in this lab > make toolbox and make shell (two readings)"
    )
  }

  private const val PERCENT = 100.0
  private const val PERIODS_PER_SECOND = 10L
  private const val WHOLE_PERCENT = 100L
}

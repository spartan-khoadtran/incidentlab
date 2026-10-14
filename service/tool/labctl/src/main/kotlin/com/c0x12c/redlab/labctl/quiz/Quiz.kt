package com.c0x12c.redlab.labctl.quiz

import com.c0x12c.redlab.labctl.Terminal
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/** Either a relative tolerance (0.03 = 3%) or an absolute one. */
sealed class Tolerance {
  data class Relative(val share: Double) : Tolerance()

  data class Absolute(val amount: Double) : Tolerance()
}

data class Question(
  val text: String,
  val answer: Double,
  val tolerance: Tolerance,
  val unit: String,
  val explanation: String,
  val doc: String
)

/** Numeric drills for the theory doc. Every run draws new numbers. */
object Quiz {

  val generators: List<(Random) -> Question> = listOf(
    ::littlePool, ::littleLatency, ::fanOut, ::fanOutInverse, ::mm1, ::mm1Inverse, ::utilizationLaw,
    ::throttle, ::lagTime, ::lagGrowth, ::lagDrain, ::poolTotal, ::averageVsPercentile, ::failFastMean,
    ::retryAmplification, ::cacheMissLoad, ::throughputFromUtilization, ::burnRate
  )

  fun run(count: Int, terminal: Terminal, random: Random = Random.Default): Int {
    val picked = generators.shuffled(random).take(count.coerceIn(1, generators.size))
    var score = 0
    picked.forEachIndexed { index, generate ->
      val question = generate(random)
      terminal.out()
      terminal.out("Question ${index + 1}/${picked.size}. ${question.text}")
      val given = askNumber(terminal, question.unit) ?: run {
        terminal.out()
        terminal.out("(Run `make quiz` in a terminal to answer.)")
        return score
      }
      val correct = isCorrect(given, question.answer, question.tolerance)
      if (correct) score += 1
      terminal.out((if (correct) "  CORRECT. " else "  NOT QUITE. ") + "Answer: ${shown(question.answer)} ${question.unit}")
      terminal.out("  ${question.explanation}")
      terminal.out("  Read again: ${question.doc}")
    }
    terminal.out()
    terminal.out("Result: $score/${picked.size}")
    return score
  }

  private fun askNumber(terminal: Terminal, unit: String): Double? {
    while (true) {
      val raw = terminal.ask("  Answer ($unit): ") ?: return null
      parseNumber(raw)?.let { return it }
      terminal.out("  Enter a number, for example 12.5")
    }
  }

  /** Accepts a comma or a dot as the decimal separator, and thousands written as 1.200.000. */
  fun parseNumber(text: String): Double? {
    var t = text.trim().replace("%", "").replace(" ", "").replace(",", ".")
    if (t.count { it == '.' } > 1) {
      t = t.replace(".", "")
    }
    return t.toDoubleOrNull()
  }

  fun isCorrect(given: Double, answer: Double, tolerance: Tolerance): Boolean =
    when (tolerance) {
      is Tolerance.Absolute -> abs(given - answer) <= tolerance.amount + EPSILON
      is Tolerance.Relative ->
        if (tolerance.share == 0.0) abs(given - answer) < EPSILON else abs(given - answer) <= tolerance.share * maxOf(abs(answer), EPSILON)
    }

  private fun littlePool(r: Random): Question {
    val rps = listOf(80, 120, 150, 200, 250, 400).random(r)
    val hold = listOf(15, 25, 40, 60, 80).random(r)
    val answer = rps * hold / MILLIS_PER_SECOND
    return Question(
      "A service receives $rps requests/s. Each request holds a DB connection for $hold ms on average. How many connections are in use at the same time, on average?",
      answer, Tolerance.Relative(0.03), "connections",
      "Little's Law: L = λ × W = $rps × ${fmt(hold / MILLIS_PER_SECOND)} s = ${fmt(answer)}. A pool smaller than this is certainly saturated; in practice you need headroom because traffic does not arrive evenly.",
      "Linking RED and USE > Little's Law"
    )
  }

  private fun littleLatency(r: Random): Question {
    val inflight = listOf(12, 30, 45, 64).random(r)
    val rps = listOf(200, 300, 600, 800).random(r)
    val answer = inflight / rps.toDouble() * MILLIS_PER_SECOND
    return Question(
      "The dashboard shows $inflight requests being handled at the same time on average, at a rate of $rps requests/s. What is the mean duration in ms?",
      answer, Tolerance.Relative(0.03), "ms",
      "W = L / λ = $inflight / $rps = ${String.format(Locale.ROOT, "%.4f", answer / MILLIS_PER_SECOND)} s ≈ ${String.format(Locale.ROOT, "%.1f", answer)} ms. Use it as a cross-check: if APM reports a much smaller mean duration, requests may be waiting somewhere APM does not measure.",
      "Linking RED and USE > Little's Law"
    )
  }

  private fun fanOut(r: Random): Question {
    val n = listOf(5, 10, 20, 50, 100).random(r)
    val pct = listOf(99.0, 99.9).random(r)
    val p = pct / PERCENT
    val answer = (1 - p.pow(n)) * PERCENT
    return Question(
      "One request calls $n independent downstream calls in parallel. Each call has a ${fmt(PERCENT - pct)}% chance of being slower than its p${fmt(pct)}. What percentage of the upstream requests hit at least one slow call?",
      answer, Tolerance.Relative(0.03), "%",
      "1 − ${fmt(p)}^$n = ${String.format(Locale.ROOT, "%.4f", answer / PERCENT)} ≈ ${String.format(Locale.ROOT, "%.1f", answer)}%. The larger the fan-out, the more the lower layer's tail is amplified.",
      "RED at many layers > Tail amplification"
    )
  }

  private fun fanOutInverse(r: Random): Question {
    val n = listOf(10, 20, 50, 100).random(r)
    val target = listOf(1, 5).random(r)
    val p = (1 - target / PERCENT).pow(1.0 / n)
    val answer = p * PERCENT
    return Question(
      "A request calls $n downstream calls in parallel. You want at most $target% of the upstream requests to hit a slow call. At which percentile must each call's 'slow' threshold sit? (answer like 99.9)",
      answer, Tolerance.Absolute(0.02), "percentile",
      "You need p^$n ≥ ${fmt(1 - target / PERCENT)}, so p = ${fmt(1 - target / PERCENT)}^(1/$n) = ${String.format(Locale.ROOT, "%.5f", p)}, about p${String.format(Locale.ROOT, "%.2f", answer)}. So with a large fan-out you must watch p99.9 and beyond at the lower layer.",
      "RED at many layers > Tail amplification"
    )
  }

  private fun mm1(r: Random): Question {
    val s = listOf(2, 5, 10, 20).random(r)
    val u = listOf(50, 60, 70, 80, 90, 95).random(r)
    val answer = s / (1 - u / PERCENT)
    return Question(
      "A resource has a service time of $s ms and runs at $u% utilization. By the M/M/1 model, what is the mean response time in ms?",
      answer, Tolerance.Relative(0.03), "ms",
      "R = S / (1 − U) = $s / (1 − ${fmt(u / PERCENT)}) = ${String.format(Locale.ROOT, "%.1f", answer)} ms, that is ${String.format(Locale.ROOT, "%.1f", 1 / (1 - u / PERCENT))} times the service time.",
      "Linking RED and USE > Queueing"
    )
  }

  private fun mm1Inverse(r: Random): Question {
    val k = listOf(2, 3, 4, 5, 10).random(r)
    val answer = (1 - 1.0 / k) * PERCENT
    return Question(
      "By M/M/1, what is the maximum utilization in % if you want the response time to stay under $k times the service time?",
      answer, Tolerance.Absolute(0.5), "%",
      "S / (1 − U) ≤ $k·S ⇒ U ≤ 1 − 1/$k = ${String.format(Locale.ROOT, "%.0f", answer)}%.",
      "Linking RED and USE > Queueing"
    )
  }

  private fun utilizationLaw(r: Random): Question {
    val iops = listOf(150, 200, 250, 300).random(r)
    val s = listOf(2, 3, 4).random(r)
    val answer = iops * s / MILLIS_PER_SECOND * PERCENT
    return Question(
      "A disk handles $iops IOPS with a mean service time of $s ms per I/O. What is the disk's (time-based) utilization in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "Utilization law: U = X × S = $iops × ${fmt(s / MILLIS_PER_SECOND)} = ${String.format(Locale.ROOT, "%.0f", answer)}%. This is the %busy iostat reports; a disk with several parallel queues can still take more work at a high busy value.",
      "USE method in detail > Time-based and capacity-based"
    )
  }

  private fun throttle(r: Random): Question {
    val limit = listOf(0.5, 1.0, 2.0).random(r)
    val threads = listOf(4, 8).random(r)
    val runMs = limit * CFS_PERIOD_MS / threads
    val answer = CFS_PERIOD_MS - runMs
    return Question(
      "A container has a CPU limit of ${fmt(limit)} core(s) (CFS period 100 ms). The app has $threads threads all busy on CPU. For how many ms per period is the app throttled?",
      answer, Tolerance.Absolute(0.5), "ms",
      "Quota = ${fmt(limit)} × 100 = ${fmt(limit * CFS_PERIOD_MS)} ms of CPU per period. $threads threads use it up after ${fmt(runMs)} ms and wait the remaining ${fmt(answer)} ms. The host may still look idle.",
      "USE at many layers > One layer hides another"
    )
  }

  private fun lagTime(r: Random): Question {
    val lag = listOf(12_000, 30_000, 90_000, 150_000).random(r)
    val rate = listOf(200, 500, 1_000, 1_500).random(r)
    val answer = lag / rate.toDouble()
    return Question(
      "Consumer lag is $lag messages and the consumer handles $rate msg/s. About how many seconds does the newest message wait?",
      answer, Tolerance.Relative(0.03), "seconds",
      "Lag in time ≈ lag / consume rate = $lag / $rate = ${fmt(answer)} s. This number is easier to compare with an SLA than a message count.",
      "RED at many layers > The async layer"
    )
  }

  private fun lagGrowth(r: Random): Question {
    val capacity = listOf(100, 120, 300).random(r)
    val produce = capacity + listOf(30, 50, 80, 120).random(r)
    val answer = ((produce - capacity) * SECONDS_PER_MINUTE).toDouble()
    return Question(
      "The producer sends $produce msg/s and the consumer handles at most $capacity msg/s. By how many messages does the lag grow per minute?",
      answer, Tolerance.Relative(0.01), "messages",
      "($produce − $capacity) × 60 = ${fmt(answer)}. Processing time is unchanged but lag grows linearly: the consumer is saturated.",
      "RED at many layers > The async layer"
    )
  }

  private fun poolTotal(r: Random): Question {
    val pods = listOf(20, 30, 40, 60).random(r)
    val per = listOf(10, 20, 25).random(r)
    val answer = (pods * per).toDouble()
    return Question(
      "Each pod has a pool of $per connections to Postgres. You scale out to $pods pods. What is the maximum total number of connections to the DB?",
      answer, Tolerance.Relative(0.0), "connections",
      "$pods × $per = ${fmt(answer)}. Compare with the DB's max_connections: raising the pool size or scaling pods both push the burden onto the DB.",
      "USE at many layers > Common examples"
    )
  }

  private fun averageVsPercentile(r: Random): Question {
    val slow = listOf(2_000, 3_000, 5_000).random(r)
    val answer = (98 * 10 + 2 * slow) / PERCENT
    return Question(
      "Out of 100 requests, 98 take 10 ms and 2 take $slow ms. What is the mean duration in ms?",
      answer, Tolerance.Relative(0.01), "ms",
      "(98 × 10 + 2 × $slow) / 100 = ${fmt(answer)} ms. The average looks fine while 2% of users wait ${fmt(slow / MILLIS_PER_SECOND)} s: always read the percentiles.",
      "RED method in detail > Duration"
    )
  }

  private fun retryAmplification(r: Random): Question {
    val p = listOf(0.3, 0.5, 0.7).random(r)
    val k = listOf(2, 3, 4).random(r)
    val answer = (1 - p.pow(k + 1)) / (1 - p)
    return Question(
      "Each attempt fails with probability ${fmt(p)} (independently). The client retries immediately, at most $k times. How many attempts does one user request produce on average?",
      answer, Tolerance.Relative(0.02), "attempts",
      "1 + p + p² + ... + p^$k = (1 − p^${k + 1}) / (1 − p) = ${String.format(Locale.ROOT, "%.2f", answer)}. The server-side rate grows by that factor although the number of users is unchanged.",
      "RED at many layers > Why Rate differs (retry)"
    )
  }

  private fun lagDrain(r: Random): Question {
    val lag = listOf(30_000, 60_000, 120_000).random(r)
    val capacity = listOf(300, 500).random(r)
    val produce = listOf(100, 200).random(r)
    val answer = lag / (capacity - produce).toDouble()
    return Question(
      "After a fix the consumer handles $capacity msg/s again, while the producer keeps sending $produce msg/s. Lag is $lag messages. In how many seconds is the lag gone?",
      answer, Tolerance.Relative(0.02), "seconds",
      "Lag drains at (capacity − produce) = ${capacity - produce} msg/s, so $lag / ${capacity - produce} = ${fmt(answer)} s. A fix that only matches the produce rate never drains the backlog.",
      "RED at many layers > The async layer"
    )
  }

  private fun failFastMean(r: Random): Question {
    val share = listOf(0.2, 0.3, 0.5).random(r)
    val answer = share * FAIL_FAST_MS + (1 - share) * NORMAL_MS
    return Question(
      "A dependency dies. ${fmt(share * PERCENT)}% of requests now fail fast in ${fmt(FAIL_FAST_MS)} ms; the rest still take ${fmt(NORMAL_MS)} ms. What is the new mean duration in ms?",
      answer, Tolerance.Absolute(0.5), "ms",
      "${fmt(share)} × ${fmt(FAIL_FAST_MS)} + ${fmt(1 - share)} × ${fmt(NORMAL_MS)} = ${fmt(answer)} ms. The mean went down while users got worse: read the duration of errors and of successes separately.",
      "RED method in detail > Duration (split errors from successes)"
    )
  }

  private fun cacheMissLoad(r: Random): Question {
    val rps = listOf(200, 500, 1_000).random(r)
    val before = listOf(0.9, 0.95, 0.98).random(r)
    val after = listOf(0.5, 0.7).random(r)
    val answer = rps * (1 - after)
    return Question(
      "A service gets $rps requests/s. Every request reads the cache, and each miss runs one DB query. The hit ratio fell from ${fmt(before * PERCENT)}% to ${fmt(after * PERCENT)}%. How many queries/s does the DB get now?",
      answer, Tolerance.Relative(0.02), "queries/s",
      "DB rate = rate × (1 − hit) = $rps × ${fmt(1 - after)} = ${fmt(answer)}, against ${fmt(rps * (1 - before))} before. The API rate did not move; the cache did.",
      "RED at many layers > Why Rate differs (cache)"
    )
  }

  private fun throughputFromUtilization(r: Random): Question {
    val u = listOf(40, 60, 75).random(r)
    val s = listOf(2, 5, 10, 20).random(r)
    val answer = u / PERCENT / (s / MILLIS_PER_SECOND)
    return Question(
      "A single-threaded worker is busy $u% of the time and its service time is $s ms per request. How many requests/s does it handle right now?",
      answer, Tolerance.Relative(0.02), "requests/s",
      "Utilization law turned around: X = U / S = ${fmt(u / PERCENT)} / ${fmt(s / MILLIS_PER_SECOND)} = ${fmt(answer)} requests/s. At 100% it would top out at ${fmt(MILLIS_PER_SECOND / s)} requests/s.",
      "USE method in detail > Time-based and capacity-based"
    )
  }

  private fun burnRate(r: Random): Question {
    val slo = listOf(99.9, 99.95).random(r)
    val errors = listOf(0.5, 1.0, 2.0).random(r)
    val budget = PERCENT - slo
    val answer = SLO_WINDOW_DAYS * budget / errors
    return Question(
      "An SLO of ${fmt(slo)}% over $SLO_WINDOW_DAYS days allows ${String.format(Locale.ROOT, "%.2f", budget)}% of requests to fail. The service runs at ${fmt(errors)}% errors right now. After how many days at this rate is the whole error budget spent?",
      answer, Tolerance.Relative(0.02), "days",
      "Burn rate = ${fmt(errors)} / ${String.format(Locale.ROOT, "%.2f", budget)} = ${fmt(errors / budget)} times the allowed rate, so the budget lasts $SLO_WINDOW_DAYS / ${fmt(errors / budget)} = ${String.format(Locale.ROOT, "%.1f", answer)} days. Page on burn rate, not on a raw error count.",
      "Combining > SLI and SLO"
    )
  }

  /** Four significant digits and no thousands separator, because the answer prompt reads a comma as the decimal point. */
  fun shown(value: Double): String = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros().toPlainString()

  private fun fmt(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  private const val EPSILON = 1e-9
  private const val SIGNIFICANT_DIGITS = 4
  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val SECONDS_PER_MINUTE = 60
  private const val CFS_PERIOD_MS = 100.0
  private const val FAIL_FAST_MS = 2.0
  private const val NORMAL_MS = 100.0
  private const val SLO_WINDOW_DAYS = 30
}

package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.math.roundToInt
import kotlin.random.Random

/** Numeric drills for docs/foundations.md: means and spread, the latency time scale, Amdahl, USL and the queue models. */
internal object FoundationsNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::harmonicMean, ::geometricMean, ::coefficientOfVariation, ::bimodalMean, ::bimodalTail, ::outlierShift, ::scaledTime,
    ::readsPerTrip, ::amdahlSpeedup, ::amdahlCap, ::uslThroughput, ::uslPeak, ::mg1Wait,
    ::sharedQueueGain, ::queueLimit, ::closedThroughput
  )

  private fun harmonicMean(r: Random): NumericQuestion {
    val rates = listOf(listOf(100, 200, 400), listOf(50, 100, 200), listOf(120, 240, 480), listOf(100, 300, 600)).random(r)
    val answer = Formulas.harmonicMean(rates.map { it.toDouble() })
    return NumericQuestion(
      "You run the same batch of 1000 requests three times. The runs reach ${rates.joinToString(", ")} req/s. What is the mean throughput over the three runs, in req/s?",
      answer, Tolerance.Relative(0.02), "req/s",
      "Equal work, so the harmonic mean: 3 / (${rates.joinToString(" + ") { "1/$it" }}) = ${shown(answer)} req/s. The arithmetic mean ${shown(rates.average())} is too high. Total requests divided by total time gives the same ${shown(answer)}.",
      "Statistics for latency > Three means"
    )
  }

  private fun geometricMean(r: Random): NumericQuestion {
    val a = listOf(2, 4).random(r)
    val b = listOf(8, 9, 16).random(r)
    val answer = Formulas.geometricMean(listOf(a.toDouble(), b.toDouble()))
    return NumericQuestion(
      "One change makes service A ${a}x faster and service B ${b}x faster. What is the mean speedup, as one factor?",
      answer, Tolerance.Relative(0.02), "times",
      "Speedups are ratios, so the geometric mean: sqrt($a × $b) = ${shown(answer)}x. The arithmetic mean ${shown((a + b) / 2.0)}x overstates it: a 0.5x and a 2x should cancel, and only the geometric mean says 1.",
      "Statistics for latency > Three means"
    )
  }

  private fun coefficientOfVariation(r: Random): NumericQuestion {
    val (mean, sd) = listOf(50 to 5, 100 to 30, 100 to 150, 200 to 60, 20 to 25, 40 to 80).random(r)
    val answer = Formulas.coefficientOfVariation(mean.toDouble(), sd.toDouble())
    val reading = if (answer > 1) "Above 1: wider than random, so a heavy tail or two humps. Read the histogram." else "Under 1: one population with a steady service time."
    return NumericQuestion(
      "An endpoint has a mean duration of $mean ms and a standard deviation of $sd ms. What is its coefficient of variation?",
      answer, Tolerance.Absolute(0.02), "CV",
      "CV = standard deviation / mean = $sd / $mean = ${shown(answer)}. $reading",
      "Statistics for latency > Spread"
    )
  }

  private fun bimodalMean(r: Random): NumericQuestion {
    val fast = listOf(2, 5, 10).random(r)
    val slow = listOf(40, 100, 200).random(r)
    val slowShare = listOf(5, 10, 20, 30).random(r)
    val answer = Formulas.meanOfTwoModes(fast.toDouble(), slow.toDouble(), slowShare.toDouble())
    return NumericQuestion(
      "$slowShare% of requests miss the cache and take $slow ms. The other ${PERCENT.toInt() - slowShare}% hit and take $fast ms. What is the mean duration in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "${shown((PERCENT - slowShare) / PERCENT)} × $fast + ${shown(slowShare / PERCENT)} × $slow = ${shown(answer)} ms. No request takes ${shown(answer)} ms: the mean sits between the two humps. p50 is $fast ms and p99 is $slow ms.",
      "Statistics for latency > Multimodal distributions"
    )
  }

  private fun bimodalTail(r: Random): NumericQuestion {
    val fast = listOf(5, 10).random(r)
    val slow = listOf(200, 500).random(r)
    val slowShare = listOf(0.5, 2.0, 5.0, 10.0).random(r)
    val answer = Formulas.quantileOfTwoModes(fast.toDouble(), slow.toDouble(), slowShare, P99)
    val explanation = if (slowShare > PERCENT - P99) {
      "p99 is where the slowest 1% start. ${shown(slowShare)}% are slow, more than 1%, so p99 sits in the slow hump: $slow ms. The mean is ${shown(Formulas.meanOfTwoModes(fast.toDouble(), slow.toDouble(), slowShare))} ms and sits in neither."
    } else {
      "Only ${shown(slowShare)}% are slow, less than 1%. So p99 is still in the fast hump, $fast ms, and only p99.9 shows the $slow ms. A tail percentile must go past the share of the slow mode to see it."
    }
    return NumericQuestion(
      "${shown(slowShare)}% of requests take $slow ms and the rest take $fast ms. What is the p99 in ms?",
      answer, Tolerance.Relative(0.0), "ms", explanation,
      "Statistics for latency > Multimodal distributions"
    )
  }

  private fun outlierShift(r: Random): NumericQuestion {
    val n = listOf(100, 1000).random(r)
    val slow = listOf(2_000, 5_000, 10_000).random(r)
    val answer = (slow - BASE_MS) / n
    return NumericQuestion(
      "$n requests take ${shown(BASE_MS)} ms each, except one that takes ${shown(slow / MILLIS_PER_SECOND)} s. By how many ms does that one request raise the mean?",
      answer, Tolerance.Relative(0.02), "ms",
      "($slow − ${shown(BASE_MS)}) / $n = ${shown(answer)} ms, so the mean goes from ${shown(BASE_MS)} to ${shown(BASE_MS + answer)} ms. The median stays at ${shown(BASE_MS)} ms. One request in $n moved the mean, so the mean is not a summary of a typical request.",
      "Statistics for latency > Outliers"
    )
  }

  private fun scaledTime(r: Random): NumericQuestion {
    val event = SCALED_EVENTS.random(r)
    val scaledSeconds = Formulas.scaledToOneSecondCycle(event.seconds, CYCLE_SECONDS)
    val answer = scaledSeconds / event.secondsPerUnit
    return NumericQuestion(
      "Scale time so that one CPU cycle of 0.3 ns lasts 1 s. How long does ${event.name} of ${event.shownTime} last on that clock, in ${event.unit}?",
      answer, Tolerance.Relative(0.05), event.unit,
      "The factor is 1 s / 0.3 ns, about 3.3 billion. ${event.shownTime} × 3.3 billion = ${shown(scaledSeconds)} s, which is ${shown(answer)} ${event.unit}. On this clock memory is minutes, a disk read is a year.",
      "Latency time scales > Scaled to human time"
    )
  }

  private fun readsPerTrip(r: Random): NumericQuestion {
    val event = SCALED_EVENTS.filter { it.seconds > MEMORY_READ_SECONDS }.random(r)
    val answer = event.seconds / MEMORY_READ_SECONDS
    return NumericQuestion(
      "A main memory read takes about 100 ns. ${event.name.replaceFirstChar { it.uppercase() }} takes about ${event.shownTime}. How many memory reads fit in one of them?",
      answer, Tolerance.Relative(0.02), "reads",
      "${event.shownTime} / 100 ns = ${shown(answer)}. Avoiding one trip (a cache, a batch) beats shaving cycles off the code.",
      "Latency time scales > What the scale says"
    )
  }

  private fun amdahlSpeedup(r: Random): NumericQuestion {
    val p = listOf(0.5, 0.8, 0.9, 0.95).random(r)
    val n = listOf(4, 8, 16, 64).random(r)
    val answer = Formulas.amdahl(p, n)
    return NumericQuestion(
      "${shown(p * PERCENT)}% of a job can run in parallel. You give it $n workers. What is the speedup by Amdahl's law, as a factor?",
      answer, Tolerance.Relative(0.02), "times",
      "S = 1 / ((1 − ${shown(p)}) + ${shown(p)} / $n) = 1 / (${shown(1 - p)} + ${shown(p / n)}) = ${shown(answer)}x. The cap with any number of workers is 1 / ${shown(1 - p)} = ${shown(1 / (1 - p))}x.",
      "Modeling and scaling laws > Amdahl's law"
    )
  }

  private fun amdahlCap(r: Random): NumericQuestion {
    val serial = listOf(2, 5, 10, 20, 25).random(r)
    val answer = PERCENT / serial
    return NumericQuestion(
      "$serial% of a request's work is serial: it runs on one thread whatever you do. What is the most speedup more workers can ever give, as a factor?",
      answer, Tolerance.Relative(0.02), "times",
      "The cap is 1 / (1 − p) = 1 / ${shown(serial / PERCENT)} = ${shown(answer)}x. Past that, more pods only add cost. Cut the serial part instead.",
      "Modeling and scaling laws > Amdahl's law"
    )
  }

  private fun uslThroughput(r: Random): NumericQuestion {
    val sigma = listOf(0.02, 0.05, 0.1).random(r)
    val kappa = listOf(0.001, 0.002, 0.005).random(r)
    val n = listOf(8, 16, 32).random(r)
    val answer = Formulas.usl(sigma, kappa, n)
    return NumericQuestion(
      "A system has contention σ = $sigma and coherency κ = $kappa. By the Universal Scalability Law, what is the throughput with $n workers, relative to one worker?",
      answer, Tolerance.Relative(0.02), "times",
      "C(N) = N / (1 + σ(N − 1) + κN(N − 1)) = $n / (1 + ${shown(sigma * (n - 1))} + ${shown(kappa * n * (n - 1))}) = ${shown(answer)}. Amdahl alone would say ${shown(n / (1 + sigma * (n - 1)))}: the coherency term is the difference. The peak is at N* = sqrt((1 − σ) / κ) = ${shown(Formulas.uslPeak(sigma, kappa))} workers.",
      "Modeling and scaling laws > Universal Scalability Law"
    )
  }

  private fun uslPeak(r: Random): NumericQuestion {
    val sigma = listOf(0.02, 0.05, 0.1).random(r)
    val kappa = listOf(0.0005, 0.001, 0.002, 0.005).random(r)
    val answer = Formulas.uslPeak(sigma, kappa)
    return NumericQuestion(
      "A ramp load test fitted σ = $sigma and κ = $kappa to the Universal Scalability Law. At how many workers does throughput peak?",
      answer, Tolerance.Relative(0.03), "workers",
      "N* = sqrt((1 − σ) / κ) = sqrt(${shown(1 - sigma)} / $kappa) = sqrt(${shown((1 - sigma) / kappa)}) = ${shown(answer)}. Past about ${answer.roundToInt()} workers, every added worker lowers throughput.",
      "Modeling and scaling laws > Universal Scalability Law"
    )
  }

  private fun mg1Wait(r: Random): NumericQuestion {
    val rho = listOf(0.5, 0.8, 0.9).random(r)
    val s = listOf(10, 20).random(r)
    val cv = listOf(0.5, 2.0, 3.0).random(r)
    val answer = Formulas.mg1Wait(rho, s.toDouble(), cv)
    return NumericQuestion(
      "A single worker runs at ${shown(rho * PERCENT)}% utilization. Its service time has a mean of $s ms and a coefficient of variation of ${shown(cv)} (M/G/1). What is the mean wait in queue, in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "M/G/1: Wq = ρ / (1 − ρ) × S × (1 + CV²) / 2. The M/M/1 part is ${shown(Formulas.mm1Wait(rho, s.toDouble()))} ms, and (1 + ${shown(cv * cv)}) / 2 = ${shown((1 + cv * cv) / 2)}. So Wq = ${shown(answer)} ms. Same load, different spread, different queue.",
      "Modeling and scaling laws > Queue models"
    )
  }

  private fun sharedQueueGain(r: Random): NumericQuestion {
    val rho = listOf(0.5, 0.7, 0.8, 0.9).random(r)
    val answer = Formulas.sharedQueueGain(rho)
    return NumericQuestion(
      "Two workers run at ${shown(rho * PERCENT)}% utilization each, each with its own queue. You merge the two queues into one shared queue (M/M/2). By what factor does the mean wait in queue fall?",
      answer, Tolerance.Relative(0.02), "times",
      "M/M/1 waits ρ / (1 − ρ) × S and M/M/2 waits ρ² / (μ(1 − ρ²)). Divide the two: (1 + ρ) / ρ = ${shown(1 + rho)} / ${shown(rho)} = ${shown(answer)} times shorter. A worker never idles while a request waits in the other worker's line.",
      "Modeling and scaling laws > Queue models"
    )
  }

  private fun queueLimit(r: Random): NumericQuestion {
    val rate = listOf(100, 200, 400, 800).random(r)
    val budgetMs = listOf(200, 250, 500, 800).random(r)
    val answer = rate * budgetMs / MILLIS_PER_SECOND
    return NumericQuestion(
      "A worker pool serves $rate requests/s. After the service time, the client timeout leaves $budgetMs ms for a request to wait in the queue. Past what queue position does a request time out before it starts?",
      answer, Tolerance.Relative(0.02), "requests",
      "A request at position n waits about n / X before it starts. n = X × wait = $rate × ${shown(budgetMs / MILLIS_PER_SECOND)} s = ${shown(answer)}. A longer queue only holds requests that will time out, so cap it near ${shown(answer)} and reject the rest fast.",
      "Modeling and scaling laws > Bounded queues and load shedding"
    )
  }

  private fun closedThroughput(r: Random): NumericQuestion {
    val users = listOf(10, 20, 50).random(r)
    val ms = listOf(25, 50, 100, 200).random(r)
    val answer = Formulas.closedThroughput(users, ms / MILLIS_PER_SECOND)
    return NumericQuestion(
      "A load tester runs $users virtual users with no think time. Each one sends its next request only after the response, which takes $ms ms on average. What is the most requests/s the test can produce?",
      answer, Tolerance.Relative(0.02), "requests/s",
      "A closed system: throughput ≤ users / response time = $users / ${shown(ms / MILLIS_PER_SECOND)} s = ${shown(answer)} requests/s. When the server slows down the test sends less, so it never shows the pile-up that open arrivals cause. This lab's loadgen is open for that reason.",
      "Modeling and scaling laws > When a model lies"
    )
  }

  private class ScaledEvent(val name: String, val seconds: Double, val shownTime: String, val unit: String, val secondsPerUnit: Double)

  private val SCALED_EVENTS = listOf(
    ScaledEvent("a main memory read", 100e-9, "100 ns", "minutes", SECONDS_PER_MINUTE),
    ScaledEvent("an SSD read", 100e-6, "100 us", "days", SECONDS_PER_DAY),
    ScaledEvent("a network round trip inside a datacenter", 0.5e-3, "0.5 ms", "days", SECONDS_PER_DAY),
    ScaledEvent("a rotational disk read", 10e-3, "10 ms", "years", SECONDS_PER_YEAR),
    ScaledEvent("a network round trip between continents", 100e-3, "100 ms", "years", SECONDS_PER_YEAR)
  )

  private const val PERCENT = 100.0
  private const val P99 = 99.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val BASE_MS = 10.0
  private const val CYCLE_SECONDS = 0.3e-9
  private const val MEMORY_READ_SECONDS = 100e-9
  private const val SECONDS_PER_MINUTE = 60.0
  private const val SECONDS_PER_DAY = 86_400.0
  private const val SECONDS_PER_YEAR = 365 * SECONDS_PER_DAY
}

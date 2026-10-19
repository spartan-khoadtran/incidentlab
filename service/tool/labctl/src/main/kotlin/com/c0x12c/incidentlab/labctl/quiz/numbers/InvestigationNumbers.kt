package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** Numeric drills for docs/investigation-guide.md. Every generator draws new numbers, so the same question is never asked twice. */
internal object InvestigationNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::littlePool, ::littleLatency, ::fanOut, ::fanOutInverse, ::fanOutQueries, ::mm1, ::mm1Inverse, ::utilizationLaw,
    ::throughputFromUtilization, ::throttle, ::burstAverage, ::oneCoreTotal, ::runQueue, ::lagTime, ::lagGrowth, ::lagDrain,
    ::partitionCapacity, ::poolTotal, ::natPorts, ::leakTime, ::serviceAsResource, ::averageVsPercentile, ::failFastMean,
    ::errorCount, ::layerGap, ::retryAmplification, ::retryLoop, ::cacheMissLoad, ::mixShift, ::kneeUtilization, ::burnRate,
    ::sliGoodShare
  )

  private fun littlePool(r: Random): NumericQuestion {
    val rps = listOf(80, 120, 150, 200, 250, 400).random(r)
    val hold = listOf(15, 25, 40, 60, 80).random(r)
    val answer = rps * hold / MILLIS_PER_SECOND
    return NumericQuestion(
      "A service receives $rps requests/s. Each request holds a DB connection for $hold ms on average. How many connections are in use at the same time, on average?",
      answer, Tolerance.Relative(0.03), "connections",
      "Little's Law: L = λ × W = $rps × ${shown(hold / MILLIS_PER_SECOND)} s = ${shown(answer)}. A pool smaller than this is certainly saturated; in practice you need headroom because traffic does not arrive evenly.",
      "Linking RED and USE > Little's Law"
    )
  }

  private fun littleLatency(r: Random): NumericQuestion {
    val inflight = listOf(12, 30, 45, 64).random(r)
    val rps = listOf(200, 300, 600, 800).random(r)
    val answer = inflight / rps.toDouble() * MILLIS_PER_SECOND
    return NumericQuestion(
      "The dashboard shows $inflight requests being handled at the same time on average, at a rate of $rps requests/s. What is the mean duration in ms?",
      answer, Tolerance.Relative(0.03), "ms",
      "W = L / λ = $inflight / $rps = ${String.format(Locale.ROOT, "%.4f", answer / MILLIS_PER_SECOND)} s ≈ ${String.format(Locale.ROOT, "%.1f", answer)} ms. Use it as a cross-check: if APM reports a much smaller mean duration, requests may be waiting somewhere APM does not measure.",
      "Linking RED and USE > Little's Law"
    )
  }

  private fun fanOut(r: Random): NumericQuestion {
    val n = listOf(5, 10, 20, 50, 100).random(r)
    val pct = listOf(99.0, 99.9).random(r)
    val p = pct / PERCENT
    val answer = (1 - p.pow(n)) * PERCENT
    return NumericQuestion(
      "One request calls $n independent downstream calls in parallel. Each call has a ${shown(PERCENT - pct)}% chance of being slower than its p${shown(pct)}. What percentage of the upstream requests hit at least one slow call?",
      answer, Tolerance.Relative(0.03), "%",
      "1 − ${shown(p)}^$n = ${String.format(Locale.ROOT, "%.4f", answer / PERCENT)} ≈ ${String.format(Locale.ROOT, "%.1f", answer)}%. The larger the fan-out, the more the lower layer's tail is amplified.",
      "RED at many layers > Tail amplification"
    )
  }

  private fun fanOutInverse(r: Random): NumericQuestion {
    val n = listOf(10, 20, 50, 100).random(r)
    val target = listOf(1, 5).random(r)
    val p = (1 - target / PERCENT).pow(1.0 / n)
    val answer = p * PERCENT
    return NumericQuestion(
      "A request calls $n downstream calls in parallel. A call counts as slow when it is above its p-th percentile. You want at most $target% of the upstream requests to hit a slow call. What must p be? (answer like 99.9)",
      answer, Tolerance.Absolute(0.02), "percentile",
      "You need p^$n ≥ ${shown(1 - target / PERCENT)}, so p = ${shown(1 - target / PERCENT)}^(1/$n) = ${String.format(Locale.ROOT, "%.5f", p)}, about p${String.format(Locale.ROOT, "%.2f", answer)}. So with a large fan-out you must watch p99.9 and beyond at the lower layer.",
      "RED at many layers > Tail amplification"
    )
  }

  private fun fanOutQueries(r: Random): NumericQuestion {
    val basket = listOf(2.5, 3.0, 4.0).random(r)
    val perItem = listOf(2, 6, 8).random(r)
    val checkouts = listOf(15, 50, 100).random(r)
    val answer = basket * perItem * checkouts
    return NumericQuestion(
      "A checkout has ${shown(basket)} items on average. A new ORM runs $perItem queries per item instead of one query per checkout. At $checkouts checkouts/s, how many queries/s does the DB get from checkouts?",
      answer, Tolerance.Relative(0.01), "queries/s",
      "${shown(basket)} × $perItem × $checkouts = ${shown(answer)} queries/s, against $checkouts before. The API rate did not move; the fan-out did. Divide DB queries/s by API requests/s to see the multiplier.",
      "RED at many layers > Why Rate differs"
    )
  }

  private fun mm1(r: Random): NumericQuestion {
    val s = listOf(2, 5, 10, 20).random(r)
    val u = listOf(50, 60, 70, 80, 90, 95).random(r)
    val answer = s / (1 - u / PERCENT)
    return NumericQuestion(
      "A resource has a service time of $s ms and runs at $u% utilization. By the M/M/1 model, what is the mean response time in ms?",
      answer, Tolerance.Relative(0.03), "ms",
      "R = S / (1 − U) = $s / (1 − ${shown(u / PERCENT)}) = ${String.format(Locale.ROOT, "%.1f", answer)} ms, that is ${String.format(Locale.ROOT, "%.1f", 1 / (1 - u / PERCENT))} times the service time.",
      "Linking RED and USE > Why latency rises before 100%"
    )
  }

  private fun mm1Inverse(r: Random): NumericQuestion {
    val k = listOf(2, 3, 4, 5, 10).random(r)
    val answer = (1 - 1.0 / k) * PERCENT
    return NumericQuestion(
      "By M/M/1, what is the maximum utilization in % if you want the response time to stay under $k times the service time?",
      answer, Tolerance.Absolute(0.5), "%",
      "S / (1 − U) ≤ $k·S ⇒ U ≤ 1 − 1/$k = ${String.format(Locale.ROOT, "%.0f", answer)}%.",
      "Linking RED and USE > Why latency rises before 100%"
    )
  }

  private fun utilizationLaw(r: Random): NumericQuestion {
    val iops = listOf(100, 150, 200, 250).random(r)
    val s = listOf(1, 2, 3).random(r)
    val answer = iops * s / MILLIS_PER_SECOND * PERCENT
    return NumericQuestion(
      "A disk handles $iops IOPS with a mean service time of $s ms per I/O. What is the disk's (time-based) utilization in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "Utilization law: U = X × S = $iops × ${shown(s / MILLIS_PER_SECOND)} = ${String.format(Locale.ROOT, "%.0f", answer)}%. This is the %busy iostat reports; a disk with several parallel queues can still take more work at a high busy value.",
      "USE method in detail > Time-based and capacity-based"
    )
  }

  private fun throughputFromUtilization(r: Random): NumericQuestion {
    val u = listOf(40, 60, 75).random(r)
    val s = listOf(2, 5, 10, 20).random(r)
    val answer = u / PERCENT / (s / MILLIS_PER_SECOND)
    return NumericQuestion(
      "A single-threaded worker is busy $u% of the time and its service time is $s ms per request. How many requests/s does it handle right now?",
      answer, Tolerance.Relative(0.02), "requests/s",
      "Utilization law turned around: X = U / S = ${shown(u / PERCENT)} / ${shown(s / MILLIS_PER_SECOND)} = ${shown(answer)} requests/s. At 100% it would top out at ${shown(MILLIS_PER_SECOND / s)} requests/s.",
      "USE method in detail > Time-based and capacity-based"
    )
  }

  private fun throttle(r: Random): NumericQuestion {
    val limit = listOf(0.5, 1.0, 2.0).random(r)
    val threads = listOf(4, 8).random(r)
    val runMs = limit * CFS_PERIOD_MS / threads
    val answer = CFS_PERIOD_MS - runMs
    return NumericQuestion(
      "A container has a CPU limit of ${shown(limit)} core(s) (CFS period 100 ms). The app has $threads threads all busy on CPU. For how many ms per period is the app throttled?",
      answer, Tolerance.Absolute(0.5), "ms",
      "Quota = ${shown(limit)} × 100 = ${shown(limit * CFS_PERIOD_MS)} ms of CPU per period. $threads threads use it up after ${shown(runMs)} ms and wait the remaining ${shown(answer)} ms. The host may still look idle.",
      "USE at many layers > One layer hides another"
    )
  }

  private fun burstAverage(r: Random): NumericQuestion {
    val busy = listOf(15, 20, 30).random(r)
    val quiet = listOf(10, 20, 30).random(r)
    val rest = SECONDS_PER_MINUTE - busy
    val answer = (busy * PERCENT + rest * quiet) / SECONDS_PER_MINUTE
    return NumericQuestion(
      "A CPU runs at 100% for $busy seconds, then at $quiet% for the remaining $rest seconds. What does the 1-minute average show, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "($busy × 100 + $rest × $quiet) / 60 = ${shown(answer)}%. During those $busy seconds every request queued, and the per-minute average hides it. Look at saturation, or at a shorter window.",
      "USE method in detail > Pitfalls"
    )
  }

  private fun oneCoreTotal(r: Random): NumericQuestion {
    val cores = listOf(4, 8, 16, 32).random(r)
    val answer = PERCENT / cores
    return NumericQuestion(
      "A host has $cores cores. One thread runs at 100% on one core and everything else is idle. What total CPU utilization in % does the host show?",
      answer, Tolerance.Absolute(0.5), "%",
      "100 / $cores = ${shown(answer)}%. The total hides a saturated core: a single-threaded app is at its limit while the host looks idle. Read CPU per core, and the event loop lag for that app.",
      "USE method in detail > Pitfalls"
    )
  }

  private fun runQueue(r: Random): NumericQuestion {
    val cores = listOf(4, 8, 16).random(r)
    val waiting = listOf(2, 4, 6, 12).random(r)
    val answer = waiting.toDouble()
    return NumericQuestion(
      "vmstat shows r = ${cores + waiting} on a $cores-core host and every core is busy. How many threads are waiting for a CPU on average?",
      answer, Tolerance.Relative(0.0), "threads",
      "The r column counts runnable threads, running and waiting: ${cores + waiting} − $cores = ${shown(answer)} waiting. A run queue longer than the core count is CPU saturation, whatever the utilization says.",
      "USE method in detail > Hardware checklist on Linux"
    )
  }

  private fun lagTime(r: Random): NumericQuestion {
    val lag = listOf(12_000, 30_000, 90_000, 150_000).random(r)
    val rate = listOf(200, 500, 1_000, 1_500).random(r)
    val answer = lag / rate.toDouble()
    return NumericQuestion(
      "Consumer lag is $lag messages and the consumer handles $rate msg/s. About how many seconds does a message produced now wait before the consumer reaches it?",
      answer, Tolerance.Relative(0.03), "seconds",
      "Lag in time ≈ lag / consume rate = $lag / $rate = ${shown(answer)} s. This number is easier to compare with an SLA than a message count.",
      "RED at many layers > The async layer"
    )
  }

  private fun lagGrowth(r: Random): NumericQuestion {
    val capacity = listOf(100, 120, 300).random(r)
    val produce = capacity + listOf(30, 50, 80, 120).random(r)
    val answer = ((produce - capacity) * SECONDS_PER_MINUTE).toDouble()
    return NumericQuestion(
      "The producer sends $produce msg/s and the consumer handles at most $capacity msg/s. By how many messages does the lag grow per minute?",
      answer, Tolerance.Relative(0.01), "messages",
      "($produce − $capacity) × 60 = ${shown(answer)}. Processing time is unchanged but lag grows linearly: the consumer is saturated.",
      "RED at many layers > The async layer"
    )
  }

  private fun lagDrain(r: Random): NumericQuestion {
    val lag = listOf(30_000, 60_000, 120_000).random(r)
    val capacity = listOf(300, 500).random(r)
    val produce = listOf(100, 200).random(r)
    val answer = lag / (capacity - produce).toDouble()
    return NumericQuestion(
      "After a fix the consumer handles $capacity msg/s again, while the producer keeps sending $produce msg/s. Lag is $lag messages. In how many seconds is the lag gone?",
      answer, Tolerance.Relative(0.02), "seconds",
      "Lag drains at (capacity − produce) = ${capacity - produce} msg/s, so $lag / ${capacity - produce} = ${shown(answer)} s. A fix that only matches the produce rate never drains the backlog.",
      "RED at many layers > The async layer"
    )
  }

  private fun partitionCapacity(r: Random): NumericQuestion {
    val partitions = listOf(3, 6, 12).random(r)
    val ms = listOf(25, 50, 200).random(r)
    val answer = partitions * MILLIS_PER_SECOND / ms
    return NumericQuestion(
      "A consumer group reads $partitions partitions, one message at a time per partition, $ms ms each. What is the most messages/s the group can process?",
      answer, Tolerance.Relative(0.01), "msg/s",
      "$partitions × 1000 / $ms = ${shown(answer)} msg/s. Above that produce rate the lag grows while processing time stays flat. More partitions or less work per message raise the ceiling.",
      "RED at many layers > The async layer"
    )
  }

  private fun poolTotal(r: Random): NumericQuestion {
    val pods = listOf(20, 30, 40, 60).random(r)
    val per = listOf(10, 20, 25).random(r)
    val answer = (pods * per).toDouble()
    return NumericQuestion(
      "Each pod has a pool of $per connections to Postgres. You scale out to $pods pods. What is the maximum total number of connections to the DB?",
      answer, Tolerance.Relative(0.0), "connections",
      "$pods × $per = ${shown(answer)}. Compare with the DB's max_connections: raising the pool size or scaling pods both push the burden onto the DB.",
      "USE at many layers > Common examples"
    )
  }

  private fun natPorts(r: Random): NumericQuestion {
    val ports = listOf(55_000, 64_000).random(r)
    val life = listOf(30, 60, 120).random(r)
    val answer = ports.toDouble() / life
    return NumericQuestion(
      "A NAT gateway has $ports ports available towards one destination. Each connection keeps its port for $life seconds. At how many new connections per second to that destination are the ports gone?",
      answer, Tolerance.Relative(0.02), "connections/s",
      "Little's Law on ports: $ports / $life s = ${shown(answer)} connections/s. Above that, outbound connections fail while CPU and bandwidth look idle.",
      "USE at many layers > Common examples"
    )
  }

  private fun leakTime(r: Random): NumericQuestion {
    val idle = listOf(200, 300).random(r)
    val kb = listOf(50, 100, 200).random(r)
    val rps = listOf(50, 100).random(r)
    val answer = (CONTAINER_LIMIT_MB - idle) * KB_PER_MB / (kb * rps)
    return NumericQuestion(
      "A container has a $CONTAINER_LIMIT_MB MB memory limit and the process sits at $idle MB. A leak keeps $kb KB per request at $rps requests/s (1 MB = 1000 KB). In how many seconds does it reach the limit?",
      answer, Tolerance.Relative(0.02), "seconds",
      "($CONTAINER_LIMIT_MB − $idle) × 1000 / ($kb × $rps) = ${shown(answer)} s. The memory curve is a sawtooth: climb, OOM kill at the cgroup limit, restart. The host may have plenty of memory left.",
      "USE at many layers > Layers and their metrics"
    )
  }

  private fun serviceAsResource(r: Random): NumericQuestion {
    val rps = listOf(40, 60, 80).random(r)
    val ms = listOf(100, 250, 500).random(r)
    val capacity = listOf(50, 100).random(r)
    val inflight = rps * ms / MILLIS_PER_SECOND
    val answer = inflight / capacity * PERCENT
    return NumericQuestion(
      "api sends $rps calls/s to pricing and each call takes $ms ms. pricing can hold $capacity requests in flight. Treating pricing as a resource of api, what is its utilization in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "In flight at pricing: $rps × ${shown(ms / MILLIS_PER_SECOND)} s = ${shown(inflight)} (Little's Law). Utilization = ${shown(inflight)} / $capacity = ${shown(answer)}%. Saturation would be api's requests waiting for a connection to pricing.",
      "USE at many layers > A service as a resource"
    )
  }

  private fun averageVsPercentile(r: Random): NumericQuestion {
    val slow = listOf(2_000, 3_000, 5_000).random(r)
    val answer = (98 * 10 + 2 * slow) / PERCENT
    return NumericQuestion(
      "Out of 100 requests, 98 take 10 ms and 2 take $slow ms. What is the mean duration in ms?",
      answer, Tolerance.Relative(0.01), "ms",
      "(98 × 10 + 2 × $slow) / 100 = ${shown(answer)} ms. The average looks fine while 2% of users wait ${shown(slow / MILLIS_PER_SECOND)} s: always read the percentiles.",
      "RED method in detail > Duration"
    )
  }

  private fun failFastMean(r: Random): NumericQuestion {
    val share = listOf(0.2, 0.3, 0.5).random(r)
    val answer = share * FAIL_FAST_MS + (1 - share) * NORMAL_MS
    return NumericQuestion(
      "A dependency dies. ${shown(share * PERCENT)}% of requests now fail fast in ${shown(FAIL_FAST_MS)} ms; the rest still take ${shown(NORMAL_MS)} ms. What is the new mean duration in ms?",
      answer, Tolerance.Absolute(0.5), "ms",
      "${shown(share)} × ${shown(FAIL_FAST_MS)} + ${shown(1 - share)} × ${shown(NORMAL_MS)} = ${shown(answer)} ms. The mean went down while users got worse: read the duration of errors and of successes separately.",
      "RED method in detail > Duration (split errors from successes)"
    )
  }

  private fun errorCount(r: Random): NumericQuestion {
    val rps = listOf(500, 2_000, 5_000).random(r)
    val ratio = listOf(0.1, 0.5).random(r)
    val minutes = listOf(5, 10).random(r)
    val seconds = minutes * SECONDS_PER_MINUTE
    val answer = rps * ratio / PERCENT * seconds
    return NumericQuestion(
      "A service serves $rps requests/s with an error ratio of ${shown(ratio)}%. How many requests failed in $minutes minutes?",
      answer, Tolerance.Relative(0.01), "requests",
      "$rps × ${shown(ratio / PERCENT)} × $seconds s = ${shown(answer)} failed requests. The ratio looks small; the count is what users feel. Watch both.",
      "RED method in detail > Errors"
    )
  }

  private fun layerGap(r: Random): NumericQuestion {
    if (r.nextBoolean()) {
      val lb = listOf(600, 900, 1_500).random(r)
      val app = listOf(80, 120, 200).random(r)
      val answer = (lb - app).toDouble()
      return NumericQuestion(
        "The LB reports a p99 of $lb ms for a route. The app's own p99 (APM) is $app ms. About how many ms does a slow request spend before the app accepts it?",
        answer, Tolerance.Relative(0.01), "ms",
        "$lb − $app = ${shown(answer)} ms. The app starts timing when the handler starts; the LB sees the queue ahead of it. That gap is saturation showing up as duration.",
        "RED at many layers > Why Duration differs"
      )
    }
    val span = listOf(200, 350, 500).random(r)
    val exec = listOf(5, 15, 40).random(r)
    val answer = (span - exec).toDouble()
    return NumericQuestion(
      "The DB span seen from the app is $span ms. Postgres reports $exec ms of execution for the same query. About how many ms are pool wait or network?",
      answer, Tolerance.Relative(0.01), "ms",
      "$span − $exec = ${shown(answer)} ms. The client-side span includes the wait for a connection; the server-side exec time does not. USE for the connection pool comes next.",
      "RED at many layers > Why Duration differs"
    )
  }

  private fun retryAmplification(r: Random): NumericQuestion {
    val p = listOf(0.3, 0.5, 0.7).random(r)
    val k = listOf(2, 3, 4).random(r)
    val answer = (1 - p.pow(k + 1)) / (1 - p)
    return NumericQuestion(
      "Each attempt fails with probability ${shown(p)} (independently). The client retries immediately, at most $k times. How many attempts does one user request produce on average?",
      answer, Tolerance.Relative(0.02), "attempts",
      "1 + p + p² + ... + p^$k = (1 − p^${k + 1}) / (1 − p) = ${String.format(Locale.ROOT, "%.2f", answer)}. The server-side rate grows by that factor although the number of users is unchanged.",
      "RED at many layers > Why Rate differs (retry)"
    )
  }

  private fun retryLoop(r: Random): NumericQuestion {
    val share = listOf(10, 30, 50).random(r)
    val answer = 1 + share / PERCENT
    return NumericQuestion(
      "Clients time out at 1 s and retry once. $share% of the server's responses now take longer than 1 s. By what factor does the request rate at the server grow?",
      answer, Tolerance.Relative(0.01), "times",
      "1 + ${shown(share / PERCENT)} = ${shown(answer)} times, with the same number of users. Slowness creates retries, retries add load, load adds slowness: the loop in the causal chain.",
      "Linking RED and USE > Causal chain"
    )
  }

  private fun cacheMissLoad(r: Random): NumericQuestion {
    val rps = listOf(200, 500, 1_000).random(r)
    val before = listOf(0.9, 0.95, 0.98).random(r)
    val after = listOf(0.5, 0.7).random(r)
    val answer = rps * (1 - after)
    return NumericQuestion(
      "A service gets $rps requests/s. Every request reads the cache, and each miss runs one DB query. The hit ratio fell from ${shown(before * PERCENT)}% to ${shown(after * PERCENT)}%. How many queries/s does the DB get now?",
      answer, Tolerance.Relative(0.02), "queries/s",
      "DB rate = rate × (1 − hit) = $rps × ${shown(1 - after)} = ${shown(answer)}, against ${shown(rps * (1 - before))} before. The API rate did not move; the cache did.",
      "RED at many layers > Why Rate differs (cache)"
    )
  }

  private fun mixShift(r: Random): NumericQuestion {
    val before = listOf(20, 30).random(r)
    val after = listOf(60, 70, 75).random(r)
    val answer = after.toDouble() / before
    return NumericQuestion(
      "The total request rate is flat. The share of the heavy endpoint (one pricing call each) went from $before% to $after%. By what factor did the load on pricing grow?",
      answer, Tolerance.Relative(0.02), "times",
      "$after / $before = ${shown(answer)} times. The total hides the mix. Split the rate by endpoint before you read it; that is the 'what' of workload characterization.",
      "Combining with other methods > Workload characterization"
    )
  }

  private fun kneeUtilization(r: Random): NumericQuestion {
    val knee = listOf(800, 1_200, 2_000).random(r)
    val share = listOf(0.45, 0.6, 0.75, 0.9).random(r)
    val now = (knee * share).roundToInt()
    val answer = now.toDouble() / knee * PERCENT
    return NumericQuestion(
      "A ramp-up load test found the knee of a service at $knee requests/s. Right now it serves $now requests/s. What is the service's utilization in %, with the knee as its capacity?",
      answer, Tolerance.Absolute(0.5), "%",
      "$now / $knee = ${shown(answer)}%. The knee from a ramp test is the denominator for a whole service's utilization; without it, requests/s is just a number.",
      "Combining with other methods > Deeper methods"
    )
  }

  private fun burnRate(r: Random): NumericQuestion {
    val slo = listOf(99.9, 99.95).random(r)
    val errors = listOf(0.5, 1.0, 2.0).random(r)
    val budget = PERCENT - slo
    val answer = SLO_WINDOW_DAYS * budget / errors
    return NumericQuestion(
      "An SLO of ${shown(slo)}% over $SLO_WINDOW_DAYS days allows ${String.format(Locale.ROOT, "%.2f", budget)}% of requests to fail. The service runs at ${shown(errors)}% errors right now. After how many days at this rate is the whole error budget spent?",
      answer, Tolerance.Relative(0.02), "days",
      "Burn rate = ${shown(errors)} / ${String.format(Locale.ROOT, "%.2f", budget)} = ${shown(errors / budget)} times the allowed rate, so the budget lasts $SLO_WINDOW_DAYS / ${shown(errors / budget)} = ${String.format(Locale.ROOT, "%.1f", answer)} days. Page on burn rate, not on a raw error count.",
      "Combining with other methods > Sibling metric sets (SLI and SLO)"
    )
  }

  private fun sliGoodShare(r: Random): NumericQuestion {
    val millions = listOf(1, 2).random(r)
    val total = millions * 1_000_000
    val failed = listOf(500, 1_500, 3_000).random(r)
    val slow = listOf(1_500, 2_500, 5_000).random(r)
    val answer = (total - failed - slow).toDouble() / total * PERCENT
    return NumericQuestion(
      "An SLI counts a request as good when it has no error and takes under 300 ms. This month: $millions million requests, $failed with an error, $slow too slow (no overlap). What is the SLI in %?",
      answer, Tolerance.Absolute(0.01), "%",
      "($total − $failed − $slow) / $total = ${shown(answer)}%. The SLI turns R, E and D into one share of good requests; the SLO is the target for that share.",
      "Combining with other methods > Sibling metric sets (SLI and SLO)"
    )
  }

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val SECONDS_PER_MINUTE = 60
  private const val CFS_PERIOD_MS = 100.0
  private const val FAIL_FAST_MS = 2.0
  private const val NORMAL_MS = 100.0
  private const val SLO_WINDOW_DAYS = 30
  private const val CONTAINER_LIMIT_MB = 512
  private const val KB_PER_MB = 1_000.0
}

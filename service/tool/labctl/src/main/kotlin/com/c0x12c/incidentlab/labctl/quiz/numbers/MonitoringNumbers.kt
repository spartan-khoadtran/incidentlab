package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.math.roundToInt
import kotlin.random.Random

/** Numeric drills for docs/monitoring.md: counters and windows, the PromQL traps, load average and units. */
internal object MonitoringNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::counterRate, ::histogramMean, ::sinceBootAverage, ::baselineChange, ::rateWindow, ::increaseFromRate, ::spikeRate,
    ::stalenessUntil, ::loadDisk, ::loadPerCore, ::observerCost, ::cpuShare, ::linkUtilization, ::msAsSeconds
  )

  internal fun counterRate(r: Random): NumericQuestion {
    val start = listOf(12_000, 48_500, 250_000).random(r)
    val perSecond = listOf(20, 60, 150).random(r)
    val seconds = listOf(30, 60, 120).random(r)
    val end = start + perSecond * seconds
    return NumericQuestion(
      "A counter read $start at 10:00:00 and $end at ${clock(seconds)}. What is its rate, per second?",
      perSecond.toDouble(), Tolerance.Relative(0.01), "per second",
      "($end − $start) / $seconds s = $perSecond per second. The raw value is the total since the process started and says nothing about now. Only the slope means something.",
      "Reading monitoring data > Counters and gauges"
    )
  }

  private fun histogramMean(r: Random): NumericQuestion {
    val countRate = listOf(40, 80, 200).random(r)
    val meanMs = listOf(50, 150, 250).random(r)
    val sumRate = countRate * meanMs / MILLIS_PER_SECOND
    return NumericQuestion(
      "rate(http_server_duration_seconds_sum[1m]) is ${shown(sumRate)} and rate(http_server_duration_seconds_count[1m]) is $countRate. What is the mean duration in ms?",
      meanMs.toDouble(), Tolerance.Relative(0.01), "ms",
      "${shown(sumRate)} s/s ÷ $countRate req/s = ${shown(meanMs / MILLIS_PER_SECOND)} s = $meanMs ms. _sum and _count are counters, so both need a rate first. The buckets are counters too.",
      "Reading monitoring data > Counters and gauges"
    )
  }

  private fun sinceBootAverage(r: Random): NumericQuestion {
    val upHours = listOf(48, 240, 720).random(r)
    val busyHours = listOf(1, 2).random(r)
    val answer = PERCENT * busyHours / upHours
    return NumericQuestion(
      "A host has been up for $upHours hours. Its disk was idle until the last $busyHours hour(s), when it ran at 100% busy. What does the first line of iostat show for the disk's utilization, in %?",
      answer, Tolerance.Relative(0.03), "%",
      "The first line is the average since boot: $busyHours × 100 / $upHours = ${shown(answer)}%. The 100% of the last hour is hidden. Throw the first line away and read the second.",
      "Reading monitoring data > Summary since boot"
    )
  }

  private fun baselineChange(r: Random): NumericQuestion {
    val lastWeek = listOf(200, 400, 800).random(r)
    val factor = listOf(0.7, 0.85, 1.2, 1.4).random(r)
    val now = (lastWeek * factor).roundToInt()
    val answer = now.toDouble() / lastWeek
    return NumericQuestion(
      "At this hour the service gets $now rps. At the same hour on the same weekday last week it got $lastWeek rps. What is now as a factor of the baseline?",
      answer, Tolerance.Relative(0.02), "times",
      "$now / $lastWeek = ${shown(answer)}. The baseline is the same point on last week's curve, not a fixed number. In PromQL, offset 1w draws it on the same chart.",
      "Reading monitoring data > Daily and weekly patterns"
    )
  }

  internal fun rateWindow(r: Random): NumericQuestion {
    val scrape = listOf(5, 10, 15, 30, 60).random(r)
    val answer = (SAFE_SCRAPES_PER_WINDOW * scrape).toDouble()
    return NumericQuestion(
      "Prometheus scrapes a target every $scrape s. What is the shortest safe window for rate(), in seconds, by the four-scrapes rule?",
      answer, Tolerance.Relative(0.0), "seconds",
      "4 × $scrape = ${shown(answer)} s. rate() needs at least two samples, and four keep one missed scrape from emptying the window. Grafana's \$__rate_interval is max(\$__interval + scrape interval, 4 × scrape interval).",
      "PromQL traps > rate needs two samples"
    )
  }

  private fun increaseFromRate(r: Random): NumericQuestion {
    val rate = listOf(0.05, 0.25, 2.5).random(r)
    val (window, label) = listOf(60 to "1m", 300 to "5m").random(r)
    val answer = rate * window
    return NumericQuestion(
      "rate(x[$label]) returns ${shown(rate)} per second. What does increase(x[$label]) return for the same window?",
      answer, Tolerance.Relative(0.01), "events",
      "increase() is rate() × window: ${shown(rate)} × $window s = ${shown(answer)}. Both are extrapolated to the window edges, so for a counter that grows by whole steps the result is often not a whole number. Never compare it with == 1.",
      "PromQL traps > increase is extrapolated"
    )
  }

  private fun spikeRate(r: Random): NumericQuestion {
    val spike = listOf(300, 600, 1_200).random(r)
    val (window, label) = listOf(60 to "1m", 300 to "5m").random(r)
    val answer = spike.toDouble() / window
    return NumericQuestion(
      "A counter jumps by $spike inside one 15 s scrape, then stays flat. irate() shows ${spike / SCRAPE_SECONDS} per second. About what does rate(x[$label]) show, per second?",
      answer, Tolerance.Relative(0.03), "per second",
      "rate() spreads the jump over the window: $spike / $window s = ${shown(answer)} per second, against irate's $spike / $SCRAPE_SECONDS = ${spike / SCRAPE_SECONDS}. irate shows a spike at its real height. That is why it is bad for alerts: a brief change can reset the for clause.",
      "PromQL traps > irate against rate"
    )
  }

  private fun stalenessUntil(r: Random): NumericQuestion {
    val minute = listOf(1, 2, 3, 4).random(r)
    val answer = (minute + LOOKBACK_MINUTES).toDouble()
    return NumericQuestion(
      "An exporter puts its own timestamps on its samples, so Prometheus writes no staleness marker for them. A series stops, and its last sample is at 10:0$minute:00. Until about what time does an instant query still return it? Answer the minutes past 10:00.",
      answer, Tolerance.Relative(0.0), "minutes past 10:00",
      "The lookback delta is $LOOKBACK_MINUTES minutes: an instant query takes the newest sample less than $LOOKBACK_MINUTES minutes old. So 10:0$minute plus $LOOKBACK_MINUTES is 10:0${minute + LOOKBACK_MINUTES}. Until then the chart draws a flat line. A series scraped without its own timestamps gets a staleness marker and ends at 10:0$minute.",
      "PromQL traps > Staleness"
    )
  }

  internal fun loadDisk(r: Random): NumericQuestion {
    val cores = listOf(4, 8).random(r)
    val load = cores + listOf(4, 5, 8).random(r)
    val runnable = listOf(2, 3, 5).random(r)
    val answer = (load - runnable).toDouble()
    return NumericQuestion(
      "A $cores-core Linux host shows a 1-minute load average of $load. vmstat shows r = $runnable on average. About how many tasks are in uninterruptible sleep (state D, usually disk I/O)?",
      answer, Tolerance.Relative(0.0), "tasks",
      "Linux load = runnable + uninterruptible. $load − $runnable = ${shown(answer)} tasks wait on I/O, not on CPU. Only $runnable tasks want the $cores cores. Confirm with vmstat's b column or /proc/pressure/io.",
      "Metrics that lie > Load average on Linux"
    )
  }

  private fun loadPerCore(r: Random): NumericQuestion {
    val (cores, load) = listOf(2 to 1.5, 4 to 3.0, 4 to 6.0, 8 to 12.0, 16 to 8.0, 16 to 20.0).random(r)
    val answer = load / cores
    val reading = if (answer > 1) "More tasks than cores on average, if they are all runnable. If part of them wait on a disk, CPU may still be free: check vmstat r and b." else "Fewer tasks than cores on average. A core can still be saturated if one thread never leaves it: read CPU per core."
    return NumericQuestion(
      "node_load1 is ${shown(load)} on a $cores-core host. What is the load per core?",
      answer, Tolerance.Absolute(0.01), "per core",
      "${shown(load)} / $cores = ${shown(answer)}. $reading",
      "Metrics that lie > Load average on Linux"
    )
  }

  private fun observerCost(r: Random): NumericQuestion {
    val micros = listOf(100, 200, 500).random(r)
    val rps = listOf(500, 2_000).random(r)
    val answer = micros * rps / MICROS_PER_SECOND
    return NumericQuestion(
      "Tracing every request costs $micros us of CPU per request. The service handles $rps requests/s. How many CPU cores does the tracing cost?",
      answer, Tolerance.Relative(0.02), "cores",
      "$micros us × $rps per s = ${shown(micros * rps / MICROS_PER_MILLI)} ms of CPU per second, so ${shown(answer)} cores. Read process_cpu_seconds_total before and after, and write the cost next to the setting.",
      "Metrics that lie > The observer effect"
    )
  }

  private fun cpuShare(r: Random): NumericQuestion {
    val (cores, limit) = listOf(0.4 to 2, 1.2 to 2, 1.6 to 2, 0.3 to 1, 0.9 to 1).random(r)
    val answer = cores / limit * PERCENT
    return NumericQuestion(
      "rate(process_cpu_seconds_total[1m]) is ${shown(cores)} for a container with a CPU limit of $limit core(s). What is its CPU utilization against the limit, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "process_cpu_seconds_total counts CPU seconds, so its rate is in cores: ${shown(cores)} of $limit = ${shown(answer)}%. Not a share of the host. Write the denominator next to the number.",
      "Metrics that lie > Units and labels"
    )
  }

  private fun linkUtilization(r: Random): NumericQuestion {
    val megabytes = listOf(25, 50, 100, 120).random(r)
    val megabits = megabytes * BITS_PER_BYTE
    val answer = megabits / GIGABIT_IN_MEGABITS * PERCENT
    return NumericQuestion(
      "A NIC is a 1 Gbit/s link. The byte counter shows $megabytes MB/s. How busy is the link, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "$megabytes MB/s × 8 = $megabits Mbit/s, and $megabits / 1000 = ${shown(answer)}%. Links are sold in bits, counters count bytes.",
      "Metrics that lie > Units and labels"
    )
  }

  private fun msAsSeconds(r: Random): NumericQuestion {
    val ms = listOf(120, 250, 800).random(r)
    val answer = ms * MILLIS_PER_SECOND
    return NumericQuestion(
      "A service records durations in milliseconds into a histogram named *_seconds. A panel shows the mean, rate(x_seconds_sum) / rate(x_seconds_count), times 1000 to get ms. The real mean is $ms ms. What does the panel show, in ms?",
      answer, Tolerance.Relative(0.0), "ms",
      "The sum holds $ms per request instead of ${shown(ms / MILLIS_PER_SECOND)}. Times 1000: ${shown(answer)} ms, a thousand times the truth. Prometheus convention is base units. Write the unit next to every number.",
      "Metrics that lie > Units and labels"
    )
  }

  private fun clock(secondsAfterTen: Int): String = "10:%02d:%02d".format(secondsAfterTen / 60, secondsAfterTen % 60)

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val MICROS_PER_MILLI = 1_000.0
  private const val MICROS_PER_SECOND = 1_000_000.0
  private const val SAFE_SCRAPES_PER_WINDOW = 4
  private const val SCRAPE_SECONDS = 15
  private const val LOOKBACK_MINUTES = 5
  private const val BITS_PER_BYTE = 8
  private const val GIGABIT_IN_MEGABITS = 1_000.0
}

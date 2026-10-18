package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/monitoring.md: one situation, four readings, one of them right. */
internal object MonitoringConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Reading monitoring data
    ChoiceQuestion(
      text = "A panel shows the raw http_server_requests_total as a line that climbs to 1,284,019 and then drops to 310. What happened?",
      answer = "The process restarted and the counter reset; wrap it in rate() and read the slope",
      others = listOf(
        "Traffic went negative for a moment",
        "The scrape interval changed",
        "The metric is a gauge and the pool drained"
      ),
      explanation = "A counter only grows and resets on restart. The raw value is the total since start. rate() handles the reset and gives the per-second slope.",
      doc = "Reading monitoring data > Counters and gauges"
    ),
    ChoiceQuestion(
      text = "Which of these is a gauge, to be read as a level and not as a rate?",
      answer = "kafka_consumer_lag_seconds, the age of the oldest unprocessed message",
      others = listOf(
        "http_server_requests_total",
        "http_server_duration_seconds_bucket",
        "kafka_consumed_total"
      ),
      explanation = "Lag is a level. Requests, consumed messages and histogram buckets are counters, and each needs a rate first.",
      doc = "Reading monitoring data > Counters and gauges"
    ),
    ChoiceQuestion(
      text = "How is a Prometheus histogram stored?",
      answer = "As a set of cumulative counters, one per bucket edge le, plus _sum and _count",
      others = listOf(
        "As a list of every observed value",
        "As one gauge per percentile",
        "As a counter of the mean"
      ),
      explanation = "le=0.5 counts everything at or under 0.5 s, including the smaller buckets. Rate each bucket, then histogram_quantile.",
      doc = "Reading monitoring data > Counters and gauges"
    ),
    ChoiceQuestion(
      text = "You run iostat -xz 1 on a host that has been up for 30 days. The first line shows 2% util and the second shows 99%. Which one is now?",
      answer = "The second: the first line is the average since boot and is useless in an incident",
      others = listOf(
        "The first: it is the steadier number",
        "Neither: iostat needs at least a minute to warm up",
        "The average of the two"
      ),
      explanation = "vmstat, iostat and mpstat print since-boot averages first. iostat -y skips that line.",
      doc = "Reading monitoring data > Summary since boot"
    ),
    ChoiceQuestion(
      text = "A teammate pastes cat /proc/net/dev once and reads 40 GB received on eth0 as a lot of traffic right now. What is wrong?",
      answer = "It is a counter since boot: read it twice and divide the difference by the seconds between the reads",
      others = listOf(
        "Nothing: 40 GB is a lot by any measure",
        "The file already shows bytes per second",
        "eth0 is always the wrong interface"
      ),
      explanation = "Any counter read once is a total since start, the same trap as the first line of vmstat.",
      doc = "Reading monitoring data > Summary since boot"
    ),
    ChoiceQuestion(
      text = "A rate alert at a fixed 400 rps fires every weekday at 9 am and never at the weekend. What is the fix?",
      answer = "Compare with the same hour last week (offset 1w): the baseline is a curve, not a number",
      others = listOf(
        "Raise the threshold to 500 rps",
        "Silence the alert on weekdays",
        "Alert on the 15-minute average instead"
      ),
      explanation = "Traffic repeats by hour and weekday. A fixed threshold either fires on the daily peak or misses a real drop at night.",
      doc = "Reading monitoring data > Daily and weekly patterns"
    ),
    ChoiceQuestion(
      text = "Today's rate is 20% below yesterday at the same hour. Before you call it an incident, what do you check?",
      answer = "The same weekday last week: yesterday may have been a holiday or an incident day",
      others = listOf(
        "Nothing: 20% is already proof",
        "The host's CPU",
        "The error rate of /health"
      ),
      explanation = "Use both baselines, because one of them is often odd. A step at a round time is a deploy or a cron, a slow ramp is users or a leak.",
      doc = "Reading monitoring data > Daily and weekly patterns"
    ),

    // ---------------------------------------------------------------- PromQL traps
    ChoiceQuestion(
      text = "A panel shows rate(x[5s]) on a lab that scrapes every 5 s. The chart is mostly gaps. Why?",
      answer = "The window usually holds fewer than two samples, and rate() returns nothing without two",
      others = listOf(
        "The counter is not increasing",
        "Prometheus is down",
        "Grafana cannot draw 5 s windows"
      ),
      explanation = "Use at least four scrape intervals, 20 s here. Grafana's \$__rate_interval does it for you.",
      doc = "PromQL traps > rate needs two samples"
    ),
    ChoiceQuestion(
      text = "What is \$__rate_interval in Grafana?",
      answer = "The larger of \$__interval + scrape interval and 4 × scrape interval, so a rate window always holds at least four samples",
      others = listOf(
        "The dashboard refresh interval",
        "Always exactly 1 minute",
        "The scrape interval of the slowest target"
      ),
      explanation = "It makes rate() safe at every zoom level. Use it instead of a fixed window in panels.",
      doc = "PromQL traps > rate needs two samples"
    ),
    ChoiceQuestion(
      text = "Why is sum(rate(x[1m])) right and rate(sum(x)[1m:]) wrong?",
      answer = "A counter reset in one instance makes the sum dip, and rate() reads that dip as a reset of the whole sum, so it shows a huge false increase",
      others = listOf(
        "sum() cannot be applied after rate()",
        "They are the same; the second is just slower",
        "rate() only accepts a single series"
      ),
      explanation = "Two instances at 1,000 each, one restarts to 5: the sum goes from 2,000 to 1,005, and rate() counts 1,005 instead of 5. Rate first, aggregate second.",
      doc = "PromQL traps > rate before sum"
    ),
    ChoiceQuestion(
      text = "A request-rate panel shows a spike of 1,000 rps for one minute at the exact moment a pod restarted. Users saw nothing. What is the likely cause?",
      answer = "The query aggregates before rate(), so the pod's counter reset looks like a jump",
      others = listOf(
        "A real burst of retries",
        "The pod replayed its requests from a queue",
        "The scrape interval shrank"
      ),
      explanation = "Check the query: sum then rate is the classic bug. The fix is sum(rate(...)).",
      doc = "PromQL traps > rate before sum"
    ),
    ChoiceQuestion(
      text = "increase(deploys_total[5m]) returns 1.07 for a counter that grew by exactly 1. Is Prometheus broken?",
      answer = "No: increase() is rate() × window, and both extrapolate to the window edges, so non-integers are normal",
      others = listOf(
        "Yes: counters must give integers",
        "The scrape missed and duplicated a sample",
        "The counter is a float gauge"
      ),
      explanation = "Do not alert on increase() == 1. For an exact count, use a window many times the scrape interval.",
      doc = "PromQL traps > increase is extrapolated"
    ),
    ChoiceQuestion(
      text = "histogram_quantile(0.99, sum(rate(x_bucket[1m]))) returns no data and no error. Why?",
      answer = "The sum dropped the le label, and histogram_quantile silently ignores series without le; use sum by (le)",
      others = listOf(
        "p99 needs at least 99 requests",
        "The rate window is too short",
        "The p99 is above the highest bucket"
      ),
      explanation = "Keep le in the by clause, and any split label next to it: sum by (route, le).",
      doc = "PromQL traps > histogram_quantile needs le"
    ),
    ChoiceQuestion(
      text = "p99 of an endpoint has sat flat at exactly 1 s for 20 minutes. The bucket edges are 0.25, 0.5, 1 and +Inf. What is the honest reading?",
      answer = "The real p99 is somewhere past 1 s: the quantile is in the +Inf bucket, and then the function returns the edge below it",
      others = listOf(
        "p99 is exactly 1.000 s",
        "The service is capped at 1 s by a timeout",
        "The histogram is broken"
      ),
      explanation = "Inside a bucket the result is a linear interpolation, so it moves. A value stuck at the highest finite edge means the quantile left the finite buckets.",
      doc = "PromQL traps > histogram_quantile needs le"
    ),
    ChoiceQuestion(
      text = "You want an alert on a slow rise in errors over 10 minutes. A teammate writes irate(errors_total[10m]) > 1. What is wrong?",
      answer = "irate uses only the last two samples whatever the window, so it flaps and resets the for clause; use rate()",
      others = listOf(
        "irate cannot take a 10m window",
        "Nothing: irate is more precise",
        "The threshold should be 0"
      ),
      explanation = "irate is for looking at a fast-moving counter over a short range. rate is for alerts and slow counters.",
      doc = "PromQL traps > irate against rate"
    ),
    ChoiceQuestion(
      text = "The request-rate line of api has a gap from 10:00 to 10:03. Which query tells you first whether api had no traffic or could not be scraped?",
      answer = "up{job=\"api\"}: it is 0 for every scrape that failed",
      others = listOf(
        "rate(http_server_requests_total[5m]), with a longer window",
        "http_server_inflight, because a gauge never goes stale",
        "count(http_server_requests_total), which counts the requests in the gap"
      ),
      explanation = "up is 1 when the target was reachable and 0 when the scrape failed. A gap with up at 0 is a target Prometheus could not reach, not a quiet service.",
      doc = "PromQL traps > Staleness"
    ),
    ChoiceQuestion(
      text = "A metric pushed through the Pushgateway shows a steady value although the job that pushed it died an hour ago. Why?",
      answer = "The Pushgateway never forgets a series: it exposes the last pushed value for ever, and up only says the gateway is up",
      others = listOf(
        "Prometheus caches pushed metrics for an hour",
        "The job is still running somewhere",
        "Staleness takes 24 hours for pushed metrics"
      ),
      explanation = "No staleness marker, no end. Check the timestamp of the last push, or alert on its age.",
      doc = "PromQL traps > Staleness"
    ),
    ChoiceQuestion(
      text = "An instant query at 10:04 returns a value for a series whose last sample is from 10:00, with no staleness marker. Why?",
      answer = "The lookback delta: an instant query takes the newest sample less than 5 minutes old",
      others = listOf(
        "Prometheus interpolates missing samples",
        "The series is a gauge, and gauges never expire",
        "The query cache served an old result"
      ),
      explanation = "Up to 5 minutes of flat line can be a ghost. Check up and the age of the last sample.",
      doc = "PromQL traps > Staleness"
    ),

    // ---------------------------------------------------------------- Metrics that lie
    ChoiceQuestion(
      text = "A 4-core Linux host shows load 9. vmstat shows r = 2 and b = 7. What is saturated?",
      answer = "Disk, not CPU: Linux adds tasks in uninterruptible sleep (state D) to the load, and 7 of the 9 wait on I/O",
      others = listOf(
        "CPU: a load above the core count always means CPU",
        "Memory: a high load means swapping",
        "Nothing: load is a percentage and 9 is low"
      ),
      explanation = "The load number alone cannot tell CPU from disk. On Linux confirm with vmstat r and b, or /proc/pressure/cpu against /proc/pressure/io.",
      doc = "Metrics that lie > Load average on Linux"
    ),
    ChoiceQuestion(
      text = "Which PSI reading confirms that a high Linux load comes from CPU and not from disk?",
      answer = "/proc/pressure/cpu rising while /proc/pressure/io stays flat",
      others = listOf(
        "/proc/loadavg above the core count",
        "/proc/pressure/memory rising",
        "The first line of /proc/stat"
      ),
      explanation = "PSI gives the share of time tasks waited for each resource over 10, 60 and 300 s. node_exporter exports it as node_pressure_*_waiting_seconds_total.",
      doc = "Metrics that lie > Load average on Linux"
    ),
    ChoiceQuestion(
      text = "After turning trace sampling to 100% to debug an incident, p99 of the service rises by 10% and the collector is now the busiest container. What is this?",
      answer = "The observer effect: monitoring costs CPU and I/O in the system you watch; measure the cost once and write it down",
      others = listOf(
        "A coincidence: tracing has no runtime cost",
        "A bug in the tracer",
        "Proof that the service was CPU-bound before"
      ),
      explanation = "Every request now builds and exports spans. The same goes for track_io_timing, a heavy dashboard against Prometheus, or strace on a busy process.",
      doc = "Metrics that lie > The observer effect"
    ),
    ChoiceQuestion(
      text = "Which Postgres setting do the docs warn can cost a lot on some platforms, with pg_test_timing to measure it?",
      answer = "track_io_timing: it asks the OS for the time around every I/O",
      others = listOf(
        "max_connections",
        "shared_buffers",
        "log_min_duration_statement at 0"
      ),
      explanation = "Timing calls are not free. Measure before you turn it on in production.",
      doc = "Metrics that lie > The observer effect"
    ),
    ChoiceQuestion(
      text = "A panel shows a mean duration of 250 s for an endpoint users describe as fine. The app records durations in milliseconds into a metric named *_seconds. What is going on?",
      answer = "A unit mix-up: 250 ms stored as 250 in a seconds metric, so every value also lands in the top bucket",
      others = listOf(
        "A real 250 s latency",
        "The histogram buckets are too small",
        "Grafana's unit setting is broken"
      ),
      explanation = "Prometheus convention is base units: seconds and bytes. Write the unit next to every number.",
      doc = "Metrics that lie > Units and labels"
    ),
    ChoiceQuestion(
      text = "rate(process_cpu_seconds_total[1m]) shows 1.6 for api, which has a 2-core limit. How busy is it?",
      answer = "80% of its limit: the rate is in cores, 1.6 of 2",
      others = listOf(
        "160%: it is over its limit",
        "1.6%: almost idle",
        "Cannot say without the host's core count"
      ),
      explanation = "CPU seconds per second are cores. Ask which denominator applies: one core, the limit, or the host.",
      doc = "Metrics that lie > Units and labels"
    ),
    ChoiceQuestion(
      text = "A NIC is a 1 Gbit/s link and the byte counter shows 100 MB/s. How busy is the link?",
      answer = "80%: 100 MB/s is 800 Mbit/s",
      others = listOf(
        "10%: 100 of 1,000",
        "100%: it is saturated",
        "It cannot be computed from bytes"
      ),
      explanation = "Links are sold in bits, counters count bytes. Multiply by 8.",
      doc = "Metrics that lie > Units and labels"
    )
  )
}

package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.API_CORES_AT_BASE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_HOLD_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults in api's runtime: the JVM heap and GC, virtual threads, and the cost of syscalls. */
internal object JvmScenarios {

  val all: List<Scenario> = listOf(
    gcPressure(),
    vthreadPinning(),
    syncLogging()
  )

  // api: -Xmx256m in a 768 MB container. The Serial collector gives the old generation
  // (Tenured Gen) 2/3 of it, about 170 MB, and the young generation 1/3.
  const val HEAP_MAX_MB = 256
  const val OLD_GEN_MAX_MB = 170

  // One carrier thread per core: api has 2 cores, so the virtual thread scheduler has 2 carriers.
  const val CARRIERS = 2

  /** Bytes the GC-pressure store spends per object: a bare 16-byte Object plus its 4-byte slot. */
  const val BYTES_PER_OBJECT = 20

  // 15 checkouts/s × (50 + hold) ms on 2 carriers: 45 to 62 ms keeps them 71 to 84% busy.
  const val PIN_HOLD_MIN_MS = 45
  const val PIN_HOLD_MAX_MS = 62

  /** Share of the 2 carriers that checkouts need when each holds one for its DB work plus [holdMs]. */
  fun carrierDemand(holdMs: Int): Double = CHECKOUTS_PER_SECOND * (BASE_CHECKOUT_HOLD_MS + holdMs) / MILLIS_PER_SECOND / CARRIERS

  /** Chance that a request finds both carriers busy: Erlang C for 2 servers, 2ρ² / (1 + ρ). */
  fun carrierBusyChance(holdMs: Int): Double {
    val rho = carrierDemand(holdMs)
    return 2 * rho * rho / (1 + rho)
  }

  /** Mean wait for a carrier in seconds, as M/M/2: the busy chance over (2 / S − λ). */
  fun carrierWaitSeconds(holdMs: Int): Double {
    val service = (BASE_CHECKOUT_HOLD_MS + holdMs) / MILLIS_PER_SECOND
    return carrierBusyChance(holdMs) / (CARRIERS / service - CHECKOUTS_PER_SECOND)
  }

  /** CPU one request spends on its own log lines, from the per-line cost measured on the lab. */
  fun logMsPerRequest(lines: Int): Double = lines * (SYSTEM_US_PER_LINE + USER_US_PER_LINE) / MICROS_PER_MILLI

  /** Cores of system time the log lines take at the base rate. */
  fun logSystemCores(lines: Int): Double = lines * BASE_RPS * SYSTEM_US_PER_LINE / MICROS_PER_SECOND

  private fun gcPressure() = Scenario(
    id = "gc_pressure",
    title = "Full GC pauses from a large churning heap",
    level = 2,
    topics = listOf("jvm", "use", "runtime"),
    params = { random, _ -> mapOf("retain" to random.nextInt(70, 86), "churn" to listOf(48, 56, 64).random(random)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("retain_mb" to p.int("retain"), "churn_kb" to p.int("churn")))) },
    decoy = 0.5,
    ticket = { p ->
      "Since ${p.startedAt}, users say a page sometimes hangs for a moment, then works. At the LB, p99.9 and the slowest requests jump in short spikes, " +
        "again and again, while p50 stays flat. api's own p99 looks normal. Traffic is normal and nothing was deployed. " +
        "api memory went up a little at the start and has been flat since, with no restarts."
    },
    hints = listOf(
      "Are all requests a little slower, or a few requests a lot slower? Compare p50, p99 and p99.9 at the LB, then the same at api.",
      "The LB sees the wait and api barely does. What stops every thread in api, its network thread too, for a fraction of a second, with no deploy?",
      "In the JVM row: GC pause p99 and full GCs per second, against live data after GC and the old gen max."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("jvm_gc"), cause = "gc_pause", partial = setOf("memory_leak")),
    explain = { p ->
      val retain = p.int("retain")
      val churn = p.int("churn")
      val objects = retain * BYTES_PER_MB / BYTES_PER_OBJECT / MILLION
      val writeMbPerSecond = BASE_RPS * churn / KB_PER_MB
      val lifetime = retain / writeMbPerSecond
      "A store in api keeps about $retain MB of tiny objects alive, about ${p.fixed(objects, 1)} million of them. " +
        "Each request writes $churn KB of new objects over random old ones: at $BASE_RPS requests/s that is ${p.fixed(writeMbPerSecond, 1)} MB/s. " +
        "An object lives about $retain / ${p.fixed(writeMbPerSecond, 1)} = ${lifetime.roundToInt()} s on average, long enough to survive a few young GCs " +
        "and be promoted, then it dies in the old generation. The heap max is $HEAP_MAX_MB MB and the old generation about $OLD_GEN_MAX_MB MB. " +
        "With the store plus the app's own data, the old generation is mostly live: a full GC frees little, so the next one comes within " +
        "tens of seconds. The Serial collector marks every live object on one thread while every thread waits, so each full GC is a pause of " +
        "a few hundred milliseconds. The share of requests that arrive during a pause is the pause over the time between pauses: " +
        "a $EXAMPLE_PAUSE_MS ms pause every $EXAMPLE_GC_INTERVAL_S s is ${p.fixed(EXAMPLE_PAUSE_MS / MILLIS_PER_SECOND / EXAMPLE_GC_INTERVAL_S * PERCENT, 1)}% of requests. " +
        "So p50 does not move, p99 rises a little, and p99.9 and the slowest requests jump to about the pause length. " +
        "api's own histogram misses most of it: its timer starts when a request reaches the filter, and during a pause no thread reads the socket. " +
        "Only the one or two requests already inside api count the pause. The LB's clock keeps running, so the LB shows the full wait. " +
        "It is not a leak: live data after GC is high but flat, and nothing restarts. " +
        "Fix: keep less on the heap (a smaller cache, or one off heap), or give the heap room for its live set plus the garbage between GCs."
    },
    steps = listOf(
      "Problem statement: a few requests hang in bursts, p50 flat, traffic normal, nothing deployed, memory flat.",
      "RED at the LB: p99.9 and the slowest requests jump every few dozen seconds, on every route at the same moments. Postgres and pricing are flat.",
      "Compare the LB with api: api's own p99 barely moves. The wait happens before api's timer starts, or while the whole process stands still.",
      "The JVM row: full GCs (MarkSweepCompact) come every few dozen seconds, with pauses of a few hundred ms, at the moments of the LB spikes.",
      "Live data after full GC takes most of the old gen max: each GC frees little, so GCs come often.",
      "Not a leak: live data is high but flat, RSS is flat and nothing restarts.",
      "The cause: a large on-heap store that keeps changing. Its objects are promoted and then die, and every full GC stops api, network thread included."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket{job=\"api\",gc=\"MarkSweepCompact\"}[5m])))",
      "sum by (gc, cause) (rate(jvm_gc_pause_seconds_count{job=\"api\"}[5m]))",
      "jvm_gc_live_data_size_bytes{job=\"api\"} / jvm_gc_max_data_size_bytes{job=\"api\"}",
      "rate(jvm_gc_pause_seconds_sum{job=\"api\"}[5m])",
      "histogram_quantile(0.999, sum by (le) (rate(envoy_http_downstream_rq_time_bucket{envoy_http_conn_manager_prefix=\"ingress\"}[5m]))) / 1000",
      "histogram_quantile(0.999, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[5m])))"
    ),
    cli = listOf(
      "curl -s http://api:8000/metrics | grep ^jvm_gc_pause_seconds_count   # the MarkSweepCompact count climbs every few dozen seconds",
      "curl -s http://api:8000/metrics | grep -E '^jvm_gc_(live|max)_data_size_bytes'   # live data after GC close to the old gen max"
    ),
    doc = "Garbage collection > GC pauses and tail latency"
  )

  private fun vthreadPinning() = Scenario(
    id = "vthread_pinning",
    title = "Virtual threads pinned by old synchronized code",
    level = 3,
    topics = listOf("jvm", "runtime", "use"),
    params = { random, _ -> mapOf("hold" to random.nextInt(PIN_HOLD_MIN_MS, PIN_HOLD_MAX_MS + 1)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("pin_db" to true, "pin_hold_ms" to p.int("hold")))) },
    marker = { "Deploy checkout-api v2.43: legacy synchronized cache put back" },
    decoy = 0.3,
    ticket = { p ->
      "Since ${p.startedAt}, p99 is up on every route, /checkout the most, and /products is slower too. Throughput is normal and there are no errors. " +
        "api CPU is under 10%, and Postgres and pricing look fine. A teammate took a thread dump: only two threads are doing any work."
    },
    hints = listOf(
      "Postgres and pricing are fine and CPU is low. So what limits how many requests api runs at the same time?",
      "api runs each request on a virtual thread, carried by as many platform threads as it has cores. What keeps a virtual thread on its carrier while it waits?",
      "In the JVM row: pinned virtual threads per second and time pinned per second, against the number of carriers."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("vthread_pinned"), cause = "vthread_pinning", partial = setOf("app_lock")),
    explain = { p ->
      val hold = p.int("hold")
      val perCheckout = BASE_CHECKOUT_HOLD_MS + hold
      val pinnedPerSecond = CHECKOUTS_PER_SECOND * perCheckout / MILLIS_PER_SECOND
      val rho = carrierDemand(hold)
      "v2.43 runs the checkout DB work and a blocking cache put (about $hold ms) inside synchronized. On JDK 21 a virtual thread that blocks " +
        "inside synchronized cannot unmount: it keeps its carrier thread for the whole wait. api has $CARRIERS cores, so $CARRIERS carriers. " +
        "Each checkout now holds one for about $BASE_CHECKOUT_HOLD_MS + $hold = $perCheckout ms. $CHECKOUTS_PER_SECOND checkouts/s need " +
        "$CHECKOUTS_PER_SECOND × $perCheckout / 1000 = ${p.fixed(pinnedPerSecond, 2)} carrier-seconds per second, so the $CARRIERS carriers are " +
        "${(rho * PERCENT).roundToInt()}% busy with pinned work alone. That is a queue with $CARRIERS servers. " +
        "A request finds both carriers busy about 2ρ² / (1 + ρ) = ${(carrierBusyChance(hold) * PERCENT).roundToInt()}% of the time, and then it waits for one to free up. " +
        "The mean wait is about that share / ($CARRIERS / ${p.fixed(perCheckout / MILLIS_PER_SECOND, 3)} s − $CHECKOUTS_PER_SECOND) = " +
        "${(carrierWaitSeconds(hold) * MILLIS_PER_SECOND).roundToInt()} ms, and the slow tail is several times longer. " +
        "That formula assumes random service times. Here they vary less, so the real wait is somewhat shorter. " +
        "Every request runs on a virtual thread, so /products and /health wait in the same queue: p99 rises on every route, and /checkout most, " +
        "because it queues for a carrier twice. CPU stays low because the pinned carriers sleep, and time pinned per second sits near " +
        "${p.fixed(pinnedPerSecond, 1)}. The busy share is under 100%, so throughput holds and nothing fails. Above 100% the queue would grow " +
        "without end, and api would stop answering, health checks included. " +
        "The lock itself is never contended (each request locks its own object), so a lock profile shows nothing. The limit is the carrier count. " +
        "Fix: use a ReentrantLock instead of synchronized, or move the blocking call out of the block. On JDK 24 and later synchronized no longer pins."
    },
    steps = listOf(
      "Problem statement: p99 up on every route, /checkout most, CPU low, Postgres and pricing fine, throughput normal, and a deploy just before.",
      "RED: Postgres exec time and pricing's own p99 are flat. The time is spent inside api.",
      "USE: CPU is a few percent of 2 cores with no throttling, and the DB pool is far from full. No classic resource is saturated.",
      "Little's Law: api's in-flight requests are above normal while nothing is busy. Requests wait for something that has no gauge of its own.",
      "The JVM row: pinned events started at the deploy, and time pinned per second sits near 1.5, most of the 2 carriers.",
      "The CPU profile in Pyroscope (service api) shows no new hot code, because the pinned carriers sleep. The lock profile is empty too: no lock is contended.",
      "The cause: synchronized around blocking work pins virtual threads, so the 2 carriers become a busy 2-server queue that every request waits in."
    ),
    evidence = listOf(
      "sum(rate(jvm_virtual_thread_pinned_seconds_total{job=\"api\"}[1m]))",
      "sum(rate(jvm_virtual_thread_pinned_total{job=\"api\"}[1m]))",
      "http_server_inflight{job=\"api\"}",
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    cli = listOf(
      "curl -s http://api:8000/metrics | grep ^jvm_virtual_thread_pinned_seconds_total; sleep 10; curl -s http://api:8000/metrics | grep ^jvm_virtual_thread_pinned_seconds_total   # grows about 13 to 16 s in 10 s: the 2 carriers are pinned most of the time",
      "top -H -b -n 1 -p $(pgrep -f checkout-api) | head -15   # CPU low on every thread: the carriers sleep, they do not compute"
    ),
    doc = "Virtual threads > Pinning"
  )

  private fun syncLogging() = Scenario(
    id = "sync_logging",
    title = "DEBUG logging turned on for a support case",
    level = 2,
    topics = listOf("runtime", "use", "cpu"),
    params = { random, _ -> mapOf("lines" to random.nextInt(MIN_LOG_LINES / LOG_LINES_STEP, MAX_LOG_LINES / LOG_LINES_STEP + 1) * LOG_LINES_STEP) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("debug_log_lines" to p.int("lines")))) },
    marker = { "Config change: api log level DEBUG for a support case" },
    decoy = 0.3,
    ticket = { p ->
      "Since ${p.startedAt}, api uses several times more CPU than usual, well over half a core, with the same traffic. " +
        "/products, normally under a millisecond at the median, now takes about ${logMsPerRequest(p.int("lines")).roundToInt()} ms. Someone proposes giving api a third core."
    },
    hints = listOf(
      "Traffic is flat and CPU is up. Where does the extra CPU go: into api's own code, or into the kernel working for api?",
      "CPU user against system in the Runtime row. What does a service do on every request that costs system time?",
      "Count api's log lines: how many lines does one request write, and what does each line cost?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("user_vs_sys"), cause = "sync_logging", partial = setOf("hot_code_path")),
    explain = { p ->
      val lines = p.int("lines")
      val perSecond = lines * BASE_RPS
      val megabytes = perSecond * LINE_BYTES / BYTES_PER_MB.toDouble()
      val system = perSecond * SYSTEM_US_PER_LINE / MICROS_PER_SECOND
      val user = perSecond * USER_US_PER_LINE / MICROS_PER_SECOND
      val perRequestMs = logMsPerRequest(lines)
      "The log level change makes api write $lines DEBUG lines per request. At $BASE_RPS requests/s that is $lines × $BASE_RPS = $perSecond lines/s, " +
        "about ${p.fixed(megabytes, 1)} MB/s of log at about $LINE_BYTES bytes per line. The console appender flushes after every line, so each line " +
        "is its own write syscall to stdout, a pipe that Docker reads, and each write happens under the appender's lock. " +
        "On the lab one line costs about ${p.fixed(USER_US_PER_LINE, 1)} µs of user CPU (formatting) and ${p.fixed(SYSTEM_US_PER_LINE, 1)} µs of system CPU (the write). " +
        "So the logging alone takes about ${p.fixed(user, 2)} cores of user time and ${p.fixed(system, 2)} cores of system time, against about " +
        "${p.fixed(API_CORES_AT_BASE, 2)} cores for the whole of api before. System time used to be a small slice, and now it is about a third of api's CPU. " +
        "Each request does $lines × ${p.fixed(SYSTEM_US_PER_LINE + USER_US_PER_LINE, 1)} µs ≈ ${p.fixed(perRequestMs, 0)} ms of extra work on its own thread, " +
        "so the /products median goes from under 1 ms to about that. /checkout already takes about 70 ms, so its p99 moves only a little. " +
        "CPU is far from the 2-core limit and not throttled, so a third core changes nothing. The Docker daemon also spends CPU reading the pipe, outside api's cgroup. " +
        "Fix: put the log level back to INFO, log less per request, or use an asynchronous appender that batches the writes."
    },
    steps = listOf(
      "Problem statement: CPU up several times, traffic flat, /products median up by 10 ms or more, and a config change just before.",
      "RED: the rate is flat and errors are zero. The extra cost comes from inside api, not from load.",
      "USE for the container: CPU is up but far from the 2-core limit, with no throttling. More cores would not help.",
      "Split the CPU by mode: system time grew from a sliver to about a third of api's CPU. Something now makes many more syscalls per request.",
      "api's log has thousands of DEBUG lines per request. Each line is one flushed write to stdout under the appender's lock.",
      "The cause: verbose synchronous logging. Put the level back, or log asynchronously in batches."
    ),
    evidence = listOf(
      "sum by (mode) (rate(cgroup_cpu_mode_seconds_total{job=\"api\"}[1m]))",
      "rate(cgroup_cpu_mode_seconds_total{job=\"api\",mode=\"system\"}[1m]) / ignoring(mode) rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m])",
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.5, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    cli = listOf(
      "grep -E '^(user|system)_usec' $(cg $(pgrep -f checkout-api))/cpu.stat; sleep 10; grep -E '^(user|system)_usec' $(cg $(pgrep -f checkout-api))/cpu.stat   # system_usec grows about half as fast as user_usec: time spent in syscalls",
      "grep ^syscw /proc/$(pgrep -f checkout-api)/io; sleep 10; grep ^syscw /proc/$(pgrep -f checkout-api)/io   # write syscalls: the rise / 10 / 50 is about the lines per request"
    ),
    doc = "System time > Logging costs"
  )

  private const val BYTES_PER_MB = 1_048_576
  private const val KB_PER_MB = 1_024.0
  private const val MILLION = 1_000_000.0
  private const val LINE_BYTES = 100

  // Measured on the lab at 427 lines per request: system time rose 0.041 cores and user time 0.073 cores for 21,350 lines/s.
  private const val SYSTEM_US_PER_LINE = 1.9
  private const val USER_US_PER_LINE = 3.4
  private const val MICROS_PER_SECOND = 1_000_000.0
  private const val MICROS_PER_MILLI = 1_000.0
  internal const val MIN_LOG_LINES = 2_000
  internal const val MAX_LOG_LINES = 3_000
  private const val LOG_LINES_STEP = 100
  private const val PERCENT = 100

  // Measured on the live lab: full GCs about every 22 s, pauses about 0.3 s.
  private const val EXAMPLE_PAUSE_MS = 300
  private const val EXAMPLE_GC_INTERVAL_S = 22
}

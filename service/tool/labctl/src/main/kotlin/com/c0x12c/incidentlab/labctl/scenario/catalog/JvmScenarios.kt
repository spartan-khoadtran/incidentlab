package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
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

  // api: 512 MB container, heap max 50% = 256 MB. The Serial collector gives the old generation
  // (Tenured Gen) 2/3 of it, about 170 MB, and the young generation 1/3.
  const val HEAP_MAX_MB = 256
  const val OLD_GEN_MAX_MB = 170

  // One carrier thread per core: api has 2 cores, so the virtual thread scheduler has 2 carriers.
  const val CARRIERS = 2

  /** Bytes the GC-pressure store spends per object: a bare 16-byte Object plus its 4-byte slot. */
  const val BYTES_PER_OBJECT = 20

  const val PIN_HOLD_MIN_MS = 110
  const val PIN_HOLD_MAX_MS = 163

  /** Share of the 2 carriers that checkouts need when each holds one for its DB work plus [holdMs]. */
  fun carrierDemand(holdMs: Int): Double = CHECKOUTS_PER_SECOND * (BASE_CHECKOUT_HOLD_MS + holdMs) / MILLIS_PER_SECOND / CARRIERS

  private fun gcPressure() = Scenario(
    id = "gc_pressure",
    title = "Full GC pauses from a large churning heap",
    level = 2,
    topics = listOf("jvm", "use", "runtime"),
    params = { random, _ -> mapOf("retain" to random.nextInt(70, 86), "churn" to listOf(48, 56, 64).random(random)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("retain_mb" to p.int("retain"), "churn_kb" to p.int("churn")))) },
    decoy = 0.5,
    ticket = { p ->
      "Since ${p.startedAt}, api's p99 jumps up in short spikes, again and again, while p50 stays flat. " +
        "Traffic is normal and nothing was deployed. api memory went up a little at the start and has been flat since, with no restarts."
    },
    hints = listOf(
      "Are all requests a little slower, or a few requests a lot slower? Compare p50 with p99 and look at the api latency heatmap.",
      "What can stop every request in api at the same moment, for a fraction of a second, with no deploy and no downstream change?",
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
        "tens of seconds. The Serial collector marks every live object on one thread while every request waits, so each full GC is a pause of " +
        "a hundred milliseconds or more. The requests in flight during a pause are the p99 spikes, and p50 does not move. " +
        "It is not a leak: live data after GC is high but flat, and nothing restarts. " +
        "Fix: keep less on the heap (a smaller cache, or one off heap), or give the heap room for its live set plus the garbage between GCs."
    },
    steps = listOf(
      "Problem statement: p99 spikes again and again, p50 flat, traffic normal, nothing deployed, memory flat.",
      "RED: the rate is flat and the LB p99 tracks api's p99. Postgres and pricing are flat, so the time is spent inside api.",
      "The api latency heatmap: a few requests at a time jump far above the main band, on every route at the same moments.",
      "Something stops the whole process. The JVM row: full GCs (MarkSweepCompact) come every few dozen seconds, with pauses above 100 ms.",
      "Live data after full GC sits close to the old gen max: each GC frees little, so GCs come often.",
      "Not a leak: live data is high but flat, RSS is flat and nothing restarts.",
      "The cause: a large on-heap store that keeps changing. Its objects are promoted and then die, and every full GC pause lands in p99."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket{job=\"api\"}[5m])))",
      "sum by (gc, cause) (rate(jvm_gc_pause_seconds_count{job=\"api\"}[5m]))",
      "jvm_gc_live_data_size_bytes{job=\"api\"} / jvm_memory_max_bytes{job=\"api\",id=\"Tenured Gen\"}",
      "rate(jvm_gc_pause_seconds_sum{job=\"api\"}[5m])",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
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
      "Since ${p.startedAt}, checkout throughput fell and p99 keeps climbing. api CPU is under 10%, and Postgres and pricing look fine. " +
        "A teammate took a thread dump: only two threads are doing any work."
    },
    hints = listOf(
      "Postgres and pricing are fine and CPU is low. So what limits how many requests api runs at the same time?",
      "api runs each request on a virtual thread, carried by as many platform threads as it has cores. What keeps a virtual thread on its carrier while it waits?",
      "In the JVM row: pinned virtual threads per second and time pinned per second, against api's in-flight requests."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("vthread_pinned"), cause = "vthread_pinning", partial = setOf("app_lock")),
    explain = { p ->
      val hold = p.int("hold")
      val perCheckout = BASE_CHECKOUT_HOLD_MS + hold
      val capacity = CARRIERS * MILLIS_PER_SECOND / perCheckout
      "v2.43 runs the checkout DB work and a blocking cache put (about $hold ms) inside synchronized. On JDK 21 a virtual thread that blocks " +
        "inside synchronized cannot unmount: it keeps its carrier thread for the whole wait. api has $CARRIERS cores, so $CARRIERS carriers. " +
        "Each checkout now holds one for about $BASE_CHECKOUT_HOLD_MS + $hold = $perCheckout ms, so at most $CARRIERS × 1000 / $perCheckout = " +
        "${p.fixed(capacity, 1)} checkouts/s can pass. $CHECKOUTS_PER_SECOND arrive per second: the demand is " +
        "${p.fixed(carrierDemand(hold), 2)} times the capacity, so the queue grows until clients time out. Every other request waits for a carrier too. " +
        "CPU stays low because the carriers sleep, and time pinned per second sits near $CARRIERS: both carriers are blocked almost all the time. " +
        "The lock itself is never contended (each request locks its own object), so a lock profile shows nothing. The limit is the carrier count. " +
        "Fix: use a ReentrantLock instead of synchronized, or move the blocking call out of the block. On JDK 24 and later synchronized no longer pins."
    },
    steps = listOf(
      "Problem statement: checkout throughput down, p99 climbing, CPU low, Postgres and pricing fine, and a deploy just before.",
      "RED: Postgres exec time and pricing's own p99 are flat. The time is spent inside api, or before it.",
      "USE: CPU is a few percent of 2 cores with no throttling, and the DB pool is far from full. No classic resource is saturated.",
      "Little's Law: api's in-flight requests climb while nothing is busy. Requests wait for something that has no gauge of its own.",
      "The JVM row: pinned events started at the deploy, and time pinned per second sits near 2, the number of carriers.",
      "If the lab has profiles (Explore > Pyroscope, service api), a wall-clock profile shows the checkout time sleeping inside the synchronized block.",
      "The cause: synchronized around blocking work pins virtual threads, so only 2 checkouts make progress at once. Demand is above that cap."
    ),
    evidence = listOf(
      "sum(rate(jvm_virtual_thread_pinned_seconds_total{job=\"api\"}[1m]))",
      "sum(rate(jvm_virtual_thread_pinned_total{job=\"api\"}[1m]))",
      "http_server_inflight{job=\"api\"}",
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "sum(rate(http_server_requests_total{job=\"api\",route=\"/checkout\"}[1m]))"
    ),
    cli = listOf(
      "curl -s http://api:8000/metrics | grep ^jvm_virtual_thread_pinned_seconds_total; sleep 10; curl -s http://api:8000/metrics | grep ^jvm_virtual_thread_pinned_seconds_total   # grows about 20 s in 10 s: both carriers pinned",
      "top -H -b -n 1 -p $(pgrep -f checkout-api) | head -15   # CPU low on every thread: the carriers sleep, they do not compute"
    ),
    doc = "Virtual threads > Pinning"
  )

  private fun syncLogging() = Scenario(
    id = "sync_logging",
    title = "DEBUG logging turned on for a support case",
    level = 2,
    topics = listOf("runtime", "use", "cpu"),
    params = { random, _ -> mapOf("lines" to random.nextInt(400, 801)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("debug_log_lines" to p.int("lines")))) },
    marker = { "Config change: api log level DEBUG for a support case" },
    decoy = 0.3,
    ticket = { p ->
      "Since ${p.startedAt}, api uses several times more CPU than usual, with the same traffic. p99 is a little higher. " +
        "Someone proposes giving api a third core."
    },
    hints = listOf(
      "Traffic is flat and CPU is up. Where does the extra CPU go: into api's own code, or into the kernel working for api?",
      "CPU user against system in the Runtime row. What does a service do on every request that costs system time?",
      "Count api's log lines: how many lines does one request write, and what does each line cost?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("user_vs_sys"), cause = "sync_logging", partial = setOf("single_thread_cpu")),
    explain = { p ->
      val lines = p.int("lines")
      val perSecond = lines * BASE_RPS
      val megabytes = perSecond * LINE_BYTES / BYTES_PER_MB.toDouble()
      "The log level change makes api write about $lines DEBUG lines per request. At $BASE_RPS requests/s that is $lines × $BASE_RPS = $perSecond lines/s, " +
        "about ${p.fixed(megabytes, 1)} MB/s of log at about $LINE_BYTES bytes per line. The console appender flushes after every line, so each line " +
        "is its own write syscall to stdout, a pipe that Docker reads, and each write happens under the appender's lock. " +
        "That is $perSecond syscalls per second: the kernel does that work, so system time grows with user time and its share of the CPU goes up. " +
        "Requests also queue for the one lock, so p99 rises a little. CPU is far from the 2-core limit and not throttled, so a third core changes nothing. " +
        "Fix: put the log level back to INFO, log less per request, or use an asynchronous appender that batches the writes."
    },
    steps = listOf(
      "Problem statement: CPU up several times, traffic flat, p99 a little higher, and a config change just before.",
      "RED: the rate is flat and errors are zero. The extra cost comes from inside api, not from load.",
      "USE for the container: CPU is up but far from the 2-core limit, with no throttling. More cores would not help.",
      "Split the CPU by mode: system time rose as much as user time or more. Something now makes many more syscalls per request.",
      "api's log has hundreds of DEBUG lines per request. Each line is one flushed write to stdout under the appender's lock.",
      "The cause: verbose synchronous logging. Put the level back, or log asynchronously in batches."
    ),
    evidence = listOf(
      "sum by (mode) (rate(cgroup_cpu_mode_seconds_total{job=\"api\"}[1m]))",
      "rate(cgroup_cpu_mode_seconds_total{job=\"api\",mode=\"system\"}[1m]) / ignoring(mode) rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m])",
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    cli = listOf(
      "grep -E '^(user|system)_usec' $(cg $(pgrep -f checkout-api))/cpu.stat; sleep 10; grep -E '^(user|system)_usec' $(cg $(pgrep -f checkout-api))/cpu.stat   # system_usec grows about as fast as user_usec: time spent in syscalls",
      "grep ^syscw /proc/$(pgrep -f checkout-api)/io; sleep 10; grep ^syscw /proc/$(pgrep -f checkout-api)/io   # write syscalls: the rise / 10 / 50 is about the lines per request"
    ),
    doc = "System time > Logging costs"
  )

  private const val BYTES_PER_MB = 1_048_576
  private const val KB_PER_MB = 1_024.0
  private const val MILLION = 1_000_000.0
  private const val LINE_BYTES = 100
}

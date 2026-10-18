package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.DEFAULT_CPU_COUNT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.SECONDS_PER_MINUTE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.round2
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults at the container and the host: CPU limits, memory limits and shared CPU. */
internal object ContainerHostScenarios {

  val all: List<Scenario> = listOf(
    cpuThrottle(),
    oom(),
    slowLeak(),
    noisyNeighbor()
  )

  private fun cpuThrottle() = Scenario(
    id = "cpu_throttle",
    title = "CPU throttling at the container",
    level = 2,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("cpus" to round2(random.nextDouble(MIN_THROTTLE_CPUS, MAX_THROTTLE_CPUS))) },
    faults = { p -> FaultSpec(docker = mapOf("api" to mapOf("cpus" to p.double("cpus")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, p99 rose on every endpoint, including /products which is normally very fast. The rate is normal. " +
        "A teammate looked at the host's CPU and said 'plenty of CPU left, it is not CPU'."
    },
    hints = listOf(
      "Rate flat, duration up on every route: suspect architecture. Do the LB and api rise together?",
      "Do USE per layer for api: host, container, runtime. Which layer's limit should utilization be measured against?",
      "Look at CPU usage against cgroup_cpu_limit_cores, and at api's share of throttled periods."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container"), signal = setOf("cgroup_cpu"), cause = "cpu_throttle"),
    explain = { p ->
      val quotaMs = (p.double("cpus") * CFS_PERIOD_MS).roundToInt()
      "The api container's CPU limit was lowered to ${p.double("cpus")} cores: each 100 ms period gets only $quotaMs ms of CPU. " +
        "When a burst of requests uses up the quota, the whole process waits for the next period. Host CPU barely changes. At the cgroup layer, " +
        "CPU use against the limit jumps several times over and the share of throttled periods climbs from 0 to 10% or more. " +
        "A 1-minute average still looks below 100% because the quota runs out inside single 100 ms periods, which is what the throttle counter shows. " +
        "This is the 'lower layer hides the upper one' case from USE at many layers."
    },
    steps = listOf(
      "Problem statement: p99 is up on every route, even /products. The rate is normal, and the host has CPU to spare.",
      "RED: the LB and api rise together and the rate is flat. Suspect architecture.",
      "USE for api at each layer: host, container, runtime. Measure utilization against the right limit.",
      "Container CPU against cgroup_cpu_limit_cores jumps, and the share of throttled periods climbs from 0 to 10% or more.",
      "The quota runs out inside single 100 ms periods, so a 1-minute average still looks under 100%.",
      "The cause: the container CPU limit was lowered. The cgroup layer hides the problem from the host view."
    ),
    evidence = listOf(
      "rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m]) / cgroup_cpu_limit_cores{job=\"api\"}",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])",
      "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / count(node_cpu_seconds_total{mode=\"idle\"})"
    ),
    doc = "USE at many layers > One layer hides another"
  )

  private fun oom() = Scenario(
    id = "oom",
    title = "Periodic OOM kill",
    level = 3,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("leak" to random.nextInt(MIN_LEAK_KB, MAX_LEAK_KB + 1)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("leak_kb" to p.int("leak")))) },
    decoy = 0.5,
    ticket = { p -> "Since ${p.startedAt}, about once a minute there is a burst of 503s that lasts a few seconds and then clears. Nobody deployed anything." },
    hints = listOf(
      "During the errors, is api still receiving requests? Look at process_start_time_seconds (the restart count).",
      "What does the api container's memory look like right before each restart, against its limit?",
      "cgroup_memory_usage_bytes against cgroup_memory_limit_bytes for api."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container", "api"), signal = setOf("memory_restarts"), cause = "oom"),
    explain = { p ->
      "Each request keeps about ${p.int("leak")} KB for ever (a memory leak). At about 50 requests/s memory grows by a few MB per second until it " +
        "reaches the container's 512 MB limit: the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down. " +
        "These are errors at the cgroup layer (USE). The sawtooth memory curve and the restart count are the main evidence."
    },
    steps = listOf(
      "Problem statement: about once a minute there is a burst of 503s that clears on its own. Nobody deployed.",
      "During the errors, is api still up? Look at the restart count from process_start_time_seconds.",
      "Watch the api container memory against its 512 MB limit right before each restart: it climbs in a sawtooth.",
      "Memory reaches the limit, the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down.",
      "The cause: a memory leak hits the container limit. These are errors at the cgroup layer in USE terms."
    ),
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "changes(process_start_time_seconds{job=\"api\"}[10m])",
      "increase(node_vmstat_oom_kill[10m])"
    ),
    doc = "USE at many layers > Layers and their metrics (container, cgroup)"
  )

  private fun slowLeak() = Scenario(
    id = "slow_leak",
    title = "Memory climbs slowly after a deploy",
    level = 2,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("leak" to random.nextInt(2, 4)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("leak_kb" to p.int("leak")))) },
    marker = { "Deploy checkout-api v2.35: product page keeps rendered fragments in memory" },
    decoy = 0.5,
    ticket = { p -> "Since ${p.startedAt}, api's memory has been climbing on the container panel. Nothing fails yet. Is this a problem, and if so, how long do we have?" },
    hints = listOf(
      "Container memory against its limit: is it a step, a sawtooth, or a straight line?",
      "How many MB per minute does it grow? Read two points 5 minutes apart.",
      "Time to the limit = (limit − used) / growth per minute."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container", "api"), signal = setOf("memory_restarts"), cause = "memory_leak", partial = setOf("oom")),
    steps = listOf(
      "Problem statement: memory climbs since a deploy, no errors yet, the question is how long until it breaks.",
      "USE for the container: memory against the 512 MB limit rises in a straight line and never drops. No restarts yet.",
      "RED is clean: no errors, normal latency. The problem is in the future, not now.",
      "Measure the growth: two points a few minutes apart give MB per minute.",
      "Predict: (limit − used) / growth gives the minutes until the OOM kill.",
      "The cause: a memory leak from the deploy. Saturation that predicts a certain incident is worth acting on before users see it."
    ),
    explain = { p ->
      val perMinute = p.int("leak") * BASE_RPS * SECONDS_PER_MINUTE / KB_PER_MB
      "Each request keeps about ${p.int("leak")} KB for ever. At about $BASE_RPS requests/s that is about ${p.fixed(perMinute, 1)} MB per minute. " +
        "Memory against the 512 MB limit climbs in a straight line with no drop, which is how a leak looks before it becomes an outage. " +
        "Time to the OOM kill = (limit − used) / growth: with about 200 MB left, about ${(LEAK_HEADROOM_MB / perMinute).roundToInt()} minutes. " +
        "This is saturation that predicts a certain incident, the kind worth a page even though no user is affected yet. " +
        "Fix: roll back v2.35, then find what keeps the memory."
    },
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "deriv(cgroup_memory_usage_bytes{job=\"api\"}[5m]) * 60 / 1024 / 1024",
      "(cgroup_memory_limit_bytes{job=\"api\"} - cgroup_memory_usage_bytes{job=\"api\"}) / deriv(cgroup_memory_usage_bytes{job=\"api\"}[5m]) / 60"
    ),
    doc = "Applying on Datadog > Monitors (saturation that predicts an incident); USE at many layers > Layers and their metrics (container, cgroup)"
  )

  private fun noisyNeighbor() = Scenario(
    id = "noisy_neighbor",
    title = "Noisy neighbour on the host",
    level = 2,
    topics = listOf("use", "host"),
    params = { _, context -> mapOf("burners" to maxOf(MIN_BURNERS, 2 * (context.cpuCount ?: DEFAULT_CPU_COUNT))) },
    faults = { p -> FaultSpec(services = mapOf("noisy" to mapOf("burners" to p.int("burners")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, every service is slower, including pricing and the worker. The rate is normal and nobody deployed anything." },
    hints = listOf(
      "When everything slows down at once, think of the shared resources.",
      "USE at the host / VM layer: CPU by mode, load against the CPU count, CPU PSI.",
      "Which container uses the most CPU? Compare cgroup_cpu_usage_seconds_total by job."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("host"), signal = setOf("host_cpu"), cause = "noisy_neighbor"),
    explain = { p ->
      "The 'noisy' container (a batch job sharing the machine, given a high CPU priority through cpu_shares) runs ${p.int("burners")} CPU-burning threads. " +
        "Host / VM CPU is near 100%, load is far above the CPU count, CPU PSI rises. Every service slows down because they all compete for CPU. " +
        "None of your services is throttled or failing: the cause sits in the shared layer."
    },
    steps = listOf(
      "Problem statement: every service is slower, including pricing and the worker. The rate is normal and nobody deployed.",
      "When everything slows down at once, think of the shared resources.",
      "USE at the host layer: CPU by mode, load against the CPU count, CPU PSI. All of them are high.",
      "Compare cgroup CPU usage by job: the noisy batch job takes most of the host's CPU.",
      "The cause: a noisy neighbour on the host. None of your services is throttled or failing; the cause sits in the shared layer."
    ),
    evidence = listOf(
      "sum by (mode) (rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "node_load1 / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "sum by (job) (rate(cgroup_cpu_usage_seconds_total[1m]))"
    ),
    doc = "USE at many layers > Layers and their metrics (hypervisor, host)"
  )

  // api averages about 0.06 cores at 50 rps, so only a limit this low runs out inside single 100 ms periods.
  private const val MIN_THROTTLE_CPUS = 0.07
  private const val MAX_THROTTLE_CPUS = 0.1
  private const val CFS_PERIOD_MS = 100

  // api idles near 300 of its 512 MB, so at 50 requests/s this leak reaches the limit about once a minute.
  private const val MIN_LEAK_KB = 90
  private const val MAX_LEAK_KB = 130
  private const val MIN_BURNERS = 4
  private const val KB_PER_MB = 1_024.0
  private const val LEAK_HEADROOM_MB = 200.0
}

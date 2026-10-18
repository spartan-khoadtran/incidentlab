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
    noisyNeighbor(),
    cpuCredits()
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
    cli = listOf(
      "cat $(cg $(pgrep -f checkout-api))/cpu.max   # quota and period in microseconds: 8000 100000 is 0.08 cores",
      "grep -E 'nr_periods|nr_throttled' $(cg $(pgrep -f checkout-api))/cpu.stat; sleep 10; grep -E 'nr_periods|nr_throttled' $(cg $(pgrep -f checkout-api))/cpu.stat   # nr_throttled grows with nr_periods",
      "pidstat -p $(pgrep -f checkout-api) 1 5   # %CPU sits at the quota, under 10% of one core",
      "mpstat 1 5   # %idle stays high: the VM has CPU to spare"
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
      val perSecond = p.int("leak") * BASE_RPS / KB_PER_MB
      "Each request keeps about ${p.int("leak")} KB for ever (a memory leak). At about $BASE_RPS requests/s memory grows by about ${p.fixed(perSecond, 1)} MB per second. " +
        "api idles at about $IDLE_RSS_TEXT of its $API_LIMIT_MB MB limit, so the leak fills the last ${LEAK_HEADROOM_MB.roundToInt()} MB or so in about " +
        "${(LEAK_HEADROOM_MB / perSecond).roundToInt()} seconds. Then the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down. " +
        "These are errors at the cgroup layer (USE). The sawtooth memory curve and the restart count are the main evidence."
    },
    steps = listOf(
      "Problem statement: about once a minute there is a burst of 503s that clears on its own. Nobody deployed.",
      "During the errors, is api still up? Look at the restart count from process_start_time_seconds.",
      "Watch the api container memory against its $API_LIMIT_MB MB limit right before each restart: it climbs in a sawtooth.",
      "Memory reaches the limit, the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down.",
      "The cause: a memory leak hits the container limit. These are errors at the cgroup layer in USE terms."
    ),
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "changes(process_start_time_seconds{job=\"api\"}[10m])",
      "increase(node_vmstat_oom_kill[10m])"
    ),
    cli = listOf(
      "dmesg -T | grep -i 'killed process' | tail -5   # Memory cgroup out of memory: Killed process ... (java), about once a minute",
      "curl -s --unix-socket /var/run/docker.sock http://localhost/containers/incidentlab-api-1/json | jq '{RestartCount, StartedAt: .State.StartedAt}'   # RestartCount climbs, StartedAt is less than a minute ago",
      "cat $(cg $(pgrep -f checkout-api))/memory.max; for i in 1 2 3 4 5 6; do cat $(cg $(pgrep -f checkout-api))/memory.current; sleep 5; done   # climbs to the limit, then starts low again"
    ),
    doc = "USE at many layers > Layers and their metrics (container, cgroup)"
  )

  private fun slowLeak() = Scenario(
    id = "slow_leak",
    title = "Memory climbs slowly after a deploy",
    level = 2,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("leak" to random.nextInt(MIN_SLOW_LEAK_KB, MAX_SLOW_LEAK_KB + 1)) },
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
      "USE for the container: memory against the $API_LIMIT_MB MB limit rises in a straight line and never drops. No restarts yet.",
      "RED is clean: no errors, normal latency. The problem is in the future, not now.",
      "Measure the growth: two points a few minutes apart give MB per minute.",
      "Predict: (limit − used) / growth gives the minutes until the OOM kill.",
      "The cause: a memory leak from the deploy. Saturation that predicts a certain incident is worth acting on before users see it."
    ),
    explain = { p ->
      val perMinute = p.int("leak") * BASE_RPS * SECONDS_PER_MINUTE / KB_PER_MB
      "Each request keeps about ${p.int("leak")} KB for ever. At about $BASE_RPS requests/s that is about ${p.fixed(perMinute, 1)} MB per minute. " +
        "Memory against the $API_LIMIT_MB MB limit climbs in a straight line with no drop, which is how a leak looks before it becomes an outage. " +
        "Time to the OOM kill = (limit − used) / growth. api idles at about $IDLE_RSS_TEXT, so about ${LEAK_HEADROOM_MB.roundToInt()} MB are left: " +
        "about ${(LEAK_HEADROOM_MB / perMinute).roundToInt()} minutes. " +
        "This is saturation that predicts a certain incident, the kind worth a page even though no user is affected yet. " +
        "Fix: roll back v2.35, then find what keeps the memory."
    },
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "deriv(cgroup_memory_usage_bytes{job=\"api\"}[5m]) * 60 / 1024 / 1024",
      "(cgroup_memory_limit_bytes{job=\"api\"} - cgroup_memory_usage_bytes{job=\"api\"}) / deriv(cgroup_memory_usage_bytes{job=\"api\"}[5m]) / 60"
    ),
    cli = listOf(
      "pidstat -r -p $(pgrep -f checkout-api) 60 3   # RSS grows by about the same amount every minute and never drops",
      "cat $(cg $(pgrep -f checkout-api))/memory.current; sleep 60; cat $(cg $(pgrep -f checkout-api))/memory.current $(cg $(pgrep -f checkout-api))/memory.max   # minutes left = (max - now) / growth per minute",
      "curl -s --unix-socket /var/run/docker.sock http://localhost/containers/incidentlab-api-1/json | jq .RestartCount   # no OOM kill yet"
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
    cli = listOf(
      "nproc; vmstat 1 5   # r far above the CPU count, id near 0",
      "pidstat -p $(pgrep -f /run/app/noisy) 1 5   # one batch job takes several cores",
      "cat $(cg $(pgrep -f /run/app/noisy))/cpu.weight $(cg $(pgrep -f checkout-api))/cpu.weight   # noisy weighs far more than the default 100",
      "cat $(cg $(pgrep -f checkout-api))/cpu.pressure   # some avg10 rises: api waits for a CPU"
    ),
    doc = "USE at many layers > Layers and their metrics (hypervisor, host)"
  )

  private fun cpuCredits() = Scenario(
    id = "cpu_credits",
    title = "A burstable VM runs out of CPU credits",
    level = 2,
    topics = listOf("use", "container", "cpu"),
    params = { random, _ ->
      mapOf("credit_seconds" to random.nextInt(MIN_CREDIT_SECONDS, MAX_CREDIT_SECONDS + 1), "cpus" to round2(random.nextDouble(MIN_THROTTLE_CPUS, MAX_THROTTLE_CPUS)))
    },
    faults = { p -> if (p.elapsedSeconds < p.int("credit_seconds")) FaultSpec() else FaultSpec(docker = mapOf("api" to mapOf("cpus" to p.double("cpus")))) },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, api moved to a smaller burstable VM to save cost. Right now every panel looks normal. The on-call lead asks you to keep " +
        "watching for a few minutes, because this kind of VM does not always stay fast. When something changes, tell us what changed and why."
    },
    hints = listOf(
      "Nothing is wrong yet. Write down the baseline now: api p99 by route, CPU usage against the limit, the throttled share. Then wait.",
      "When p99 rises on every route with a flat rate and no deploy, do USE for api at the container layer: did the usage move, or did the limit?",
      "cgroup_cpu_limit_cores for api fell and the throttled share climbs. The VM ran at full speed on credits, then dropped to its baseline."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container"), signal = setOf("cgroup_cpu"), cause = "cpu_credits", partial = setOf("cpu_throttle")),
    explain = { p ->
      val minutes = p.int("credit_seconds") / SECONDS_PER_MINUTE.toDouble()
      "A burstable VM earns CPU credits while it stays under its baseline and spends them above it. api ran at full speed for about ${p.fixed(minutes, 1)} minutes " +
        "on the credits the VM had. Then the balance hit zero and, in standard mode, the VM fell to its baseline. From then on api gets about ${p.double("cpus")} cores: p99 rises on every " +
        "route, the rate stays flat, and nobody deployed. The lab has no hypervisor, so it models the drop as a lower CPU quota: cgroup_cpu_limit_cores falls and the " +
        "throttled share climbs. On a real burstable instance the quota does not move. The hypervisor withholds the CPU, so steal time rises inside the VM and the " +
        "credit balance metric runs down to zero before the slowdown. Either way the work did not change, the ceiling did. " +
        "Fix: a non-burstable instance for a service with steady load, or unlimited mode, which keeps bursting and bills the extra CPU."
    },
    steps = listOf(
      "Problem statement: api moved to a burstable VM, nothing is wrong yet, and the lead expects that to change. Write down the baseline before it does.",
      "Watch RED: after a few minutes p99 rises on every route, the rate is flat, and there is no deploy marker. Suspect architecture.",
      "USE for api at the container layer: CPU usage against the limit and the throttled share both jump, while the host still has idle CPU.",
      "Compare the limit itself before and after: cgroup_cpu_limit_cores fell. The work is the same, the ceiling moved.",
      "Ask why a ceiling moves by itself: a burstable VM spends credits above its baseline and drops to the baseline when they run out.",
      "The cause: out of CPU credits. On a real cloud VM you would see steal time rise and the credit balance fall. The lab shows it as a lower quota."
    ),
    evidence = listOf(
      "cgroup_cpu_limit_cores{job=\"api\"}",
      "rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m]) / cgroup_cpu_limit_cores{job=\"api\"}",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    doc = "CPU shares and quotas > CPU credits on burstable instances"
  )

  // api averages about 0.06 cores at 50 rps, so only a limit this low runs out inside single 100 ms periods.
  private const val MIN_THROTTLE_CPUS = 0.07
  private const val MAX_THROTTLE_CPUS = 0.1
  private const val CFS_PERIOD_MS = 100

  // api idles at 450 to 500 of its 768 MB, so at 50 requests/s this leak fills the rest in 35 to 50 s.
  internal const val MIN_LEAK_KB = 120
  internal const val MAX_LEAK_KB = 170
  internal const val MIN_SLOW_LEAK_KB = 2
  internal const val MAX_SLOW_LEAK_KB = 3
  internal const val API_LIMIT_MB = 768
  internal const val LEAK_HEADROOM_MB = 280.0
  private const val IDLE_RSS_TEXT = "450 to 500 MB"
  private const val MIN_BURNERS = 4
  internal const val KB_PER_MB = 1_024.0

  // How long the VM runs at full speed before its credits are gone.
  private const val MIN_CREDIT_SECONDS = 120
  private const val MAX_CREDIT_SECONDS = 240
}

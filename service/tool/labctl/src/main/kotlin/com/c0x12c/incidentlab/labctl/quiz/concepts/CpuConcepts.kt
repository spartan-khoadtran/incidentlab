package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/cpu.md: shares, credits, busy loops, short processes, locks and thread states. */
internal object CpuConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- CPU shares and quotas
    ChoiceQuestion(
      text = "A container has cpu_shares 65536, the others keep the default. It is idle. What does the setting do to the other containers right now?",
      answer = "Nothing: a weight only splits the CPU between containers that all want to run",
      others = listOf(
        "It reserves most of the host's CPU, so the others run slower",
        "It throttles the others once they pass their share",
        "It gives the idle container a head start when it wakes up"
      ),
      explanation = "Shares and weights only matter under contention. An idle container gives nothing and takes nothing, whatever its weight.",
      doc = "CPU shares and quotas > Shares only matter under contention"
    ),
    ChoiceQuestion(
      text = "Container A (cpu.weight 300) and container B (cpu.weight 100) both want all of a one-core host. How is the core split?",
      answer = "A gets about 75%, B about 25%: in proportion to the weights",
      others = listOf(
        "A gets the core, B waits until A sleeps",
        "Each gets 50%: weights are only a hint",
        "B is throttled for 75 ms of every 100 ms period"
      ),
      explanation = "Under contention the CPU is split by weight: 300 / (300 + 100) is 75%. A weight is not a quota, so nobody is throttled.",
      doc = "CPU shares and quotas > Shares only matter under contention"
    ),
    ChoiceQuestion(
      text = "What is the difference between a CPU quota (cpu.max) and a CPU weight (cpu.weight)?",
      answer = "The quota is a hard ceiling checked every period, even on an idle host; the weight only matters when containers compete",
      others = listOf(
        "They are two names for the same limit",
        "The weight is the hard limit, the quota is the priority",
        "The quota applies to memory, the weight to CPU"
      ),
      explanation = "Over its quota a container is throttled whatever the host does. A weight changes nothing until the host runs out of CPU.",
      doc = "CPU shares and quotas > Shares only matter under contention"
    ),
    ChoiceQuestion(
      text = "A service moved to a burstable VM in standard mode a few hours ago. Now p99 is up on every route, the rate is flat and nobody deployed. What happened?",
      answer = "The VM spent its CPU credits and fell to its baseline CPU",
      others = listOf(
        "A memory leak reached the limit",
        "The load balancer lost a backend",
        "The database ran out of connections"
      ),
      explanation = "Above its baseline a burstable VM spends credits. When the balance hits zero it drops to the baseline: same code, same load, less CPU.",
      doc = "CPU shares and quotas > CPU credits on burstable instances"
    ),
    ChoiceQuestion(
      text = "Which two signals show that a burstable VM ran out of CPU credits?",
      answer = "The credit balance metric at zero, and steal time rising inside the VM",
      others = listOf(
        "The container's throttled share and its OOM kill count",
        "Event loop lag and GC pause time",
        "Connection pool wait time and DB lock waiters"
      ),
      explanation = "The hypervisor holds the CPU back, which the guest kernel counts as steal time. The cloud's credit balance metric runs down before the slowdown.",
      doc = "CPU shares and quotas > CPU credits on burstable instances"
    ),
    ChoiceQuestion(
      text = "Inside a VM out of CPU credits, CPU utilization seen by the guest went down while latency went up. Why?",
      answer = "The guest cannot use CPU it does not get: the missing time shows as steal, not as utilization",
      others = listOf(
        "The application fixed itself",
        "The guest clock is wrong",
        "Fewer requests arrive, so the CPU is idle"
      ),
      explanation = "Utilization counts the CPU the guest ran on. Time the hypervisor withheld is steal time, so the guest looks less busy while it waits.",
      doc = "CPU shares and quotas > CPU credits on burstable instances"
    ),
    ChoiceQuestion(
      text = "A burstable VM runs in unlimited mode and its credit balance is at zero. It still needs more than its baseline. What happens?",
      answer = "It keeps bursting, and the extra CPU is billed",
      others = listOf(
        "It falls to its baseline until it earns credits again",
        "The hypervisor stops the VM",
        "The guest kernel throttles every process to the baseline"
      ),
      explanation = "Only standard mode falls to the baseline. Unlimited mode spends surplus credits and bills them when the average stays above the baseline.",
      doc = "CPU shares and quotas > CPU credits on burstable instances"
    ),

    // ---------------------------------------------------------------- CPU busy with no work
    ChoiceQuestion(
      text = "A Kafka consumer's process CPU jumped to one full core. Lag is 0, the consume rate and processing time are unchanged. What is the likely cause?",
      answer = "The loop polls with no wait and spins while the topic is empty",
      others = listOf(
        "The producer sends more than the consumer can handle",
        "The consumer got slower per message",
        "A poison message is retried for ever"
      ),
      explanation = "A poll with a zero timeout returns at once and is called again. Utilization without saturation is a busy loop, not a backlog.",
      doc = "CPU busy with no work > Busy polling"
    ),
    ChoiceQuestion(
      text = "A CPU alarm fires on a consumer at 100% of its limit, and lag is 0. Should you add consumers?",
      answer = "No: a spinning consumer is not saturated, and every new consumer would spin too",
      others = listOf(
        "Yes: 100% CPU always means the consumer is at capacity",
        "Yes, but only one at a time",
        "No: add CPU to the existing consumer instead"
      ),
      explanation = "Saturation (lag) decides whether more consumers help. Here nothing waits, so the fix is a blocking poll with a timeout.",
      doc = "CPU busy with no work > Busy polling"
    ),
    ChoiceQuestion(
      text = "What tells a busy-polling consumer apart from an overloaded one?",
      answer = "The overloaded one has growing lag and a consume rate pinned at capacity; the spinning one has neither",
      others = listOf(
        "The overloaded one uses more memory",
        "The spinning one logs errors",
        "There is no way to tell from metrics"
      ),
      explanation = "Both show a busy CPU. Only the overloaded one shows saturation, which is what RED and USE together are for.",
      doc = "CPU busy with no work > Busy polling"
    ),
    ChoiceQuestion(
      text = "The host's fork rate rose 100 times, but top shows no new process. Why?",
      answer = "Each process lives a few milliseconds and is gone between two snapshots of top",
      others = listOf(
        "top hides processes from other containers",
        "The forks fail, so no process exists",
        "The fork counter is broken"
      ),
      explanation = "top and ps take snapshots. Short-lived processes fall between them, while the kernel's fork counter (node_forks_total) counts every one.",
      doc = "CPU busy with no work > Short-lived processes"
    ),
    ChoiceQuestion(
      text = "Which metric or tool sees a storm of processes that each live a few milliseconds?",
      answer = "node_forks_total from /proc/stat, or an exec tracer like execsnoop",
      others = listOf(
        "top sorted by CPU",
        "The container's throttled share",
        "Event loop lag"
      ),
      explanation = "The processes field of /proc/stat counts every fork and clone since boot. An exec tracer lists each short process with its command line.",
      doc = "CPU busy with no work > Short-lived processes"
    ),
    ChoiceQuestion(
      text = "A fork storm runs on the host and api is slower at the same time. The host's load is under the CPU count and api is not throttled. What do you conclude?",
      answer = "The storm is real but harmless to api; keep looking for the cause of the slowness",
      others = listOf(
        "The storm is the cause: stop it first and see",
        "The host metrics are wrong",
        "api is slow because forks use its memory"
      ),
      explanation = "A busy host is only a problem when someone waits behind it. With no saturation, the first thing you found is not the problem.",
      doc = "CPU busy with no work > Short-lived processes"
    ),

    // ---------------------------------------------------------------- Off-CPU time
    ChoiceQuestion(
      text = "/checkout p99 is 8 times higher. api CPU is low, the pool, pricing and Postgres are flat, and /products is fine. Where is the time?",
      answer = "Inside api, waiting on a lock: an off-CPU wait that no hardware resource shows",
      others = listOf(
        "In Postgres: the span is hiding it",
        "In the load balancer queue",
        "In the network between api and pricing"
      ),
      explanation = "Latency up with CPU low and downstreams flat is off-CPU time. The resource is a software one: look at threads waiting and lock wait time.",
      doc = "Off-CPU time > Waiting on a lock"
    ),
    ChoiceQuestion(
      text = "A lock is held 60 ms per request and is busy 90% of the time. What does M/M/1 say about the mean wait for it?",
      answer = "About 9 hold times, so about 540 ms",
      others = listOf(
        "About one hold time, 60 ms",
        "Nothing: a lock is not a queue",
        "About 90 ms"
      ),
      explanation = "W = S × ρ / (1 − ρ) = 60 × 0.9 / 0.1 = 540 ms. The wait grows fast as the lock's utilization nears 100%.",
      doc = "Off-CPU time > Waiting on a lock"
    ),
    ChoiceQuestion(
      text = "Two locks run at 90% utilization with the same mean hold time. One holds for a random time, the other for exactly the same time on every call. Which waits less?",
      answer = "The fixed hold: its mean wait is half the M/M/1 wait",
      others = listOf(
        "The random hold: random times cancel out",
        "Both wait the same: only utilization matters",
        "Neither waits: a lock below 100% has no queue"
      ),
      explanation = "With fixed hold times W = S × ρ / (2 × (1 − ρ)), half of the M/M/1 value. Variation in the hold time is part of what builds the queue.",
      doc = "Off-CPU time > Waiting on a lock"
    ),
    ChoiceQuestion(
      text = "What are the three USE numbers of a lock?",
      answer = "Utilization is the share of time it is held, saturation is the threads waiting and their wait time, errors are lock timeouts and deadlocks",
      others = listOf(
        "Utilization is CPU, saturation is memory, errors are 5xx",
        "A lock has no USE numbers; use RED instead",
        "Utilization is the number of locks, saturation is the number of threads, errors are exceptions"
      ),
      explanation = "A lock is a software resource like a pool. rate(held_seconds_total) gives its utilization directly.",
      doc = "Off-CPU time > Waiting on a lock"
    ),
    ChoiceQuestion(
      text = "Which fix for a hot lock comes first by the performance mantras?",
      answer = "Do not take it on the request path: refresh in the background, or once per interval instead of once per request",
      others = listOf(
        "Make the code under the lock faster",
        "Add CPU to the container",
        "Add more instances of the service"
      ),
      explanation = "Work you remove costs nothing. Making the hold shorter or sharding the lock comes after taking the lock off the request path.",
      doc = "Off-CPU time > Waiting on a lock"
    ),
    ChoiceQuestion(
      text = "A thread is runnable but not running. Which signals show that state?",
      answer = "A run queue above the core count, PSI cpu, throttled periods, steal time",
      others = listOf(
        "Lock wait time and threads waiting",
        "iowait and procs_blocked",
        "Nothing: a runnable thread is running"
      ),
      explanation = "Runnable means ready with no CPU to run on: the host is full, the quota is spent, or the hypervisor holds the CPU back.",
      doc = "Off-CPU time > Thread states"
    ),
    ChoiceQuestion(
      text = "A slow request's thread spends its time BLOCKED on the same monitor as many other threads, in a thread dump. What is the next method?",
      answer = "USE for that lock: who holds it, how long, how many wait",
      others = listOf(
        "CPU profiling and a flame graph",
        "RED of the downstream services",
        "Scale out the service"
      ),
      explanation = "The thread state picks the tool. On CPU means profile, runnable means a CPU limit, blocked on a lock means USE for the lock, blocked on I/O means RED of the downstream.",
      doc = "Off-CPU time > Thread states"
    ),
    ChoiceQuestion(
      text = "In which thread state is a consumer that blocks in a poll with a 500 ms timeout while the topic is empty?",
      answer = "Sleeping in the kernel: it uses no CPU until data arrives or the timeout passes",
      others = listOf(
        "On CPU",
        "Runnable, waiting for a CPU",
        "Blocked on disk I/O, so iowait rises"
      ),
      explanation = "A blocking poll waits in the kernel on its sockets. A Java thread dump still says RUNNABLE, but the thread uses no CPU. Remove the timeout and the same thread spins on CPU: that is the busy poll.",
      doc = "Off-CPU time > Thread states"
    )
  )
}

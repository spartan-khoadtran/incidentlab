package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/runtime.md, at least one per subsection. */
internal object RuntimeConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Garbage collection
    ChoiceQuestion(
      text = "api has a 256 MB heap on the Serial collector, yet jvm_memory_max_bytes for Tenured Gen says about 170 MB. Why?",
      answer = "The heap is split: the young generation takes 1/3 (NewRatio=2) and the old generation 2/3",
      others = listOf(
        "The JVM keeps 86 MB free for metaspace inside the heap",
        "The metric is wrong: Tenured Gen always equals the heap max",
        "Docker gives the JVM only 170 MB of the 768 MB limit"
      ),
      explanation = "Tenured Gen is the old generation. Eden and the two survivor spaces hold the other third.",
      doc = "Garbage collection > Heap and GC in this lab"
    ),
    ChoiceQuestion(
      text = "Which collection's cost grows with the number of live objects in the whole heap?",
      answer = "The full GC (MarkSweepCompact): it marks every live object, then slides them together",
      others = listOf(
        "The young GC (Copy): it copies every object in the heap",
        "Neither: GC cost depends only on the heap size",
        "Both cost the same, because Serial uses one thread for each"
      ),
      explanation = "A young GC copies only the live young objects. A full GC walks the whole live set on one thread while the app waits.",
      doc = "Garbage collection > Heap and GC in this lab"
    ),
    ChoiceQuestion(
      text = "p99 jumps in short spikes, p50 is flat, every route spikes at the same moments, and downstreams are flat. What do you check first in the runtime?",
      answer = "GC pause p99 and the count of full GCs, lined up with the spikes",
      others = listOf(
        "The DB pool, because every route uses it",
        "The event loop lag, because it is the only thing shared by all routes",
        "Nothing in the runtime: spikes on every route mean the load balancer"
      ),
      explanation = "A GC pause stops every request at once, so the slow requests appear on all routes together while most requests stay fast.",
      doc = "Garbage collection > GC pauses and tail latency"
    ),
    ChoiceQuestion(
      text = "A 50 ms full GC runs once every 10 minutes. Will p99 over a 1-minute window show it?",
      answer = "Rarely: it hits far less than 1% of requests. p99.9 or a latency heatmap can show it",
      others = listOf(
        "Yes: every pause shows in p99",
        "No, and nothing else can show it either",
        "Yes, but only in p50"
      ),
      explanation = "The share of requests a pause hits is about pause time over the time between pauses: 0.05 / 600 is under 0.01%.",
      doc = "Garbage collection > GC pauses and tail latency"
    ),
    ChoiceQuestion(
      text = "Which objects fill the old generation with garbage?",
      answer = "Medium-lived ones: they survive a few young GCs, get promoted, then die",
      others = listOf(
        "Short-lived ones: they die in Eden",
        "Long-lived ones: they stay alive for ever",
        "Large arrays only, because small objects never reach the old generation"
      ),
      explanation = "Short-lived objects are almost free and long-lived ones are paid once. Medium-lived ones are copied, promoted, and then need a full GC to be freed.",
      doc = "Garbage collection > Allocation rate and live data"
    ),
    ChoiceQuestion(
      text = "Live data after full GC sits flat at 90% of the old generation max for an hour. Full GCs come every 20 s. What is it?",
      answer = "The heap is too small for its live set: each full GC frees little, so they come often",
      others = listOf(
        "A memory leak that will end in an OOM kill within minutes",
        "Healthy: a full heap means the JVM uses its memory well",
        "A young generation problem: Eden is too small"
      ),
      explanation = "A leak climbs after every GC. A high but flat line is a live set that leaves little room for garbage between full GCs.",
      doc = "Garbage collection > Allocation rate and live data"
    ),

    // ---------------------------------------------------------------- Virtual threads
    ChoiceQuestion(
      text = "api has a 2-core CPU limit and runs requests on virtual threads. How many carrier threads does the default scheduler use?",
      answer = "2: the default parallelism is the number of available processors",
      others = listOf(
        "One per request, so thousands",
        "256, the scheduler's maximum pool size",
        "1, because Micronaut runs on one event loop"
      ),
      explanation = "jdk.virtualThreadScheduler.parallelism defaults to the available processors, which the JVM reads from the container's CPU limit.",
      doc = "Virtual threads > Carriers and parallelism"
    ),
    ChoiceQuestion(
      text = "1000 requests on virtual threads all wait on a slow socket read, with 2 carriers. What happens to the carriers?",
      answer = "Each virtual thread unmounts while it waits, so the 2 carriers stay free for other work",
      others = listOf(
        "Both carriers block, and the other 998 requests wait for them",
        "The JVM starts 1000 platform threads",
        "The requests fail, because 2 carriers cannot hold 1000 threads"
      ),
      explanation = "Blocking I/O unmounts a virtual thread that is not pinned. Only CPU work and pinned blocking keep a carrier busy.",
      doc = "Virtual threads > Carriers and parallelism"
    ),
    ChoiceQuestion(
      text = "On JDK 21, which of these keeps a virtual thread on its carrier while it waits?",
      answer = "Thread.sleep inside a synchronized block",
      others = listOf(
        "Thread.sleep outside any lock",
        "Waiting on a ReentrantLock",
        "A socket read with no lock held"
      ),
      explanation = "Inside synchronized the virtual thread is pinned, so any blocking call blocks the carrier too. The scheduler does not add carriers for it.",
      doc = "Virtual threads > Pinning"
    ),
    ChoiceQuestion(
      text = "A service moves from JDK 21 to JDK 24. What changes for blocking calls inside synchronized?",
      answer = "They no longer pin (JEP 491). Native frames and class initialization still can",
      others = listOf(
        "Nothing: synchronized always pins",
        "synchronized is removed from the language",
        "Every virtual thread gets its own carrier"
      ),
      explanation = "JEP 491 lets virtual threads hold monitors without their carrier. The jdk.VirtualThreadPinned event stays for the cases that still pin.",
      doc = "Virtual threads > Pinning"
    ),
    ChoiceQuestion(
      text = "CPU is at 3%, in-flight requests keep growing, the DB pool is mostly empty, and pinned seconds per second sits near 2. api has 2 carriers. What limits throughput?",
      answer = "Both carriers are blocked by pinned virtual threads, so every other request waits for a carrier",
      others = listOf(
        "The DB pool, because in-flight is growing",
        "CPU, because the JVM hides the real usage",
        "The network to Postgres"
      ),
      explanation = "Seconds pinned per second is about the number of carriers blocked at once. At the carrier count nothing else can run.",
      doc = "Virtual threads > Reading the signals"
    ),
    ChoiceQuestion(
      text = "A lock profile shows no contention, because each request locks its own object. Can the service still be stuck on that synchronized block?",
      answer = "Yes: nobody waits for the lock, but pinned requests block the carriers everyone needs",
      others = listOf(
        "No: without lock contention synchronized costs nothing",
        "No: virtual threads never block inside synchronized",
        "Only if the lock object is static"
      ),
      explanation = "Pinning limits concurrency through the carriers, not through the lock. Look at the pinned metrics, not at lock waits.",
      doc = "Virtual threads > Reading the signals"
    ),

    // ---------------------------------------------------------------- System time
    ChoiceQuestion(
      text = "Traffic is flat. api's CPU doubled, and the system share went from 20% to 50%. What changed?",
      answer = "Each request now makes many more syscalls: writes, small packets, or log lines",
      others = listOf(
        "The business logic got slower",
        "GC got more expensive",
        "Nothing: system time is the host's, not the container's"
      ),
      explanation = "System time is the kernel working for the process. GC and app code are user time.",
      doc = "System time > User and system CPU"
    ),
    ChoiceQuestion(
      text = "Why can process_cpu_seconds_total not tell you whether the extra CPU is syscalls?",
      answer = "It adds user and system time together. The split needs cpu.stat's user_usec and system_usec",
      others = listOf(
        "It only counts the main thread",
        "It counts CPU of the whole host",
        "It only counts time spent in GC"
      ),
      explanation = "The lab exports the split as cgroup_cpu_mode_seconds_total{mode}.",
      doc = "System time > User and system CPU"
    ),
    ChoiceQuestion(
      text = "A service logs 500 lines per request through logback's console appender with its defaults. What does each line cost beyond formatting?",
      answer = "One flushed write syscall to stdout, taken under the appender's single lock",
      others = listOf(
        "Nothing: lines are buffered and written once a second",
        "A disk fsync per line",
        "A network call to the log collector"
      ),
      explanation = "immediateFlush is true by default, so every line is its own write, and threads take turns on one lock.",
      doc = "System time > Logging costs"
    ),
    ChoiceQuestion(
      text = "You move verbose logging to logback's AsyncAppender with its defaults. What happens when the queue is nearly full?",
      answer = "It drops TRACE, DEBUG and INFO events, and a caller blocks once the queue is full",
      others = listOf(
        "It grows the queue without limit",
        "It writes the events synchronously instead",
        "It drops WARN and ERROR events first"
      ),
      explanation = "The async appender moves the writes to one background thread. Its default queue holds 256 events.",
      doc = "System time > Logging costs"
    )
  )
}

package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/profiling.md: one situation, four readings, one of them right. */
internal object ProfilingConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- CPU profiling
    ChoiceQuestion(
      text = "Why can a sampling profiler stay on in production, when an instrumenting profiler usually cannot?",
      answer = "Its cost is set by the sampling interval, not by how many calls the code makes",
      others = listOf(
        "It only runs when CPU is above 80%",
        "It reads the stacks from the logs, so the app does no extra work",
        "It profiles a copy of the process, not the real one"
      ),
      explanation = "A sampling profiler takes one stack per interval. An instrumenting profiler times every call, so its cost grows with the call count and can change the result.",
      doc = "CPU profiling > Sampling profilers"
    ),
    ChoiceQuestion(
      text = "async-profiler runs inside a Docker container with the default seccomp profile. Why pick the itimer engine over cpu?",
      answer = "The cpu engine needs perf_event_open, which the default seccomp profile blocks. itimer needs no special permission",
      others = listOf(
        "itimer is more accurate than cpu and also shows kernel frames",
        "cpu only works for C programs, not for the JVM",
        "itimer samples every thread, running or not, so it also sees waits"
      ),
      explanation = "itimer uses setitimer(ITIMER_PROF): a signal per interval of process CPU time. It works in any container. The price: no kernel frames, and samples are not spread perfectly evenly between threads.",
      doc = "CPU profiling > Sampling profilers"
    ),
    ChoiceQuestion(
      text = "In a CPU flame graph, what does the width of a box mean?",
      answer = "The share of samples where that function was on the stack, its own work plus its callees",
      others = listOf(
        "How long one call of the function takes",
        "When the function ran: left is earlier, right is later",
        "How many times the function was called"
      ),
      explanation = "Width is a share of samples, so a share of CPU time. The x-axis is not time, and a profile does not count calls.",
      doc = "CPU profiling > Reading a flame graph"
    ),
    ChoiceQuestion(
      text = "One box is 60% wide, but its children together are 57% wide. Where is the hot code?",
      answer = "In the children: the box itself has only about 3% self time, so look one level down",
      others = listOf(
        "In the box itself: it is the widest one",
        "Nowhere: 60% is normal for an entry point",
        "In the root box at the top, because it is always the widest"
      ),
      explanation = "Self time is total time minus the callees. A wide box with wide children is an entry point. The hot code is where a wide box has no wide children.",
      doc = "CPU profiling > Reading a flame graph"
    ),
    ChoiceQuestion(
      text = "A slow trace shows api's server span at 90 ms. Its child spans (Redis, Postgres, pricing) add up to 30 ms. What is the next step?",
      answer = "Open a CPU profile of api over the same time range: the other 60 ms is api's own code, which no span covers",
      others = listOf(
        "Add more child spans until one of them is slow, then stop",
        "Blame the network between api and its downstreams",
        "Check Postgres first, because the DB is usually the cause"
      ),
      explanation = "A trace only sees code that has a span. The trace gives the service and the time range. The profile of that service over that range names the code.",
      doc = "CPU profiling > From a trace to a profile"
    ),
    ChoiceQuestion(
      text = "In this lab, how do you go from a slow trace to the matching profile?",
      answer = "By hand: open Pyroscope for the same service_name and the same time range",
      others = listOf(
        "Click 'Profiles for this span': every span has its own flame graph",
        "Search Pyroscope for the trace id",
        "You cannot: traces and profiles have nothing in common"
      ),
      explanation = "Span profiles need a bridge library that sets the pyroscope.profile.id span attribute. The lab does not install it, so the link is the service and the time range.",
      doc = "CPU profiling > From a trace to a profile"
    ),

    // ---------------------------------------------------------------- Off-CPU profiling
    ChoiceQuestion(
      text = "p99 doubled. Process CPU is flat, and the CPU profile looks the same as yesterday. What do you reach for?",
      answer = "Traces and a wall-clock or lock profile: the extra time is spent waiting, which a CPU profile cannot see",
      others = listOf(
        "A longer CPU profile, until the hot function shows up",
        "A bigger CPU limit",
        "Nothing: a flat CPU profile proves the service is fine"
      ),
      explanation = "Wall-clock time is time on CPU plus time off CPU. A thread that waits on a lock, a socket or a pool uses no CPU, so it gets no CPU samples.",
      doc = "Off-CPU profiling > Wall-clock and off-CPU time"
    ),
    ChoiceQuestion(
      text = "A wall-clock profile of the worker is full of thread pool stacks parked in a wait. What does that mean?",
      answer = "Probably nothing: wall-clock mode samples idle threads too, so filter to the threads that serve work",
      others = listOf(
        "The pool is saturated: every thread is busy",
        "The worker is CPU bound",
        "The profiler is broken, because a parked thread cannot be sampled"
      ),
      explanation = "Wall mode samples every thread, running or not. An idle pool thread waiting for work looks like a big wait in the profile.",
      doc = "Off-CPU profiling > Wall-clock and off-CPU time"
    ),
    ChoiceQuestion(
      text = "The Java agent runs with lock=10ms. What does that setting do?",
      answer = "It takes a lock sample each time the total lock wait grows by 10 ms",
      others = listOf(
        "It records only the waits longer than 10 ms and drops the rest",
        "It stops the profiler after 10 ms of lock wait",
        "It makes every lock wait 10 ms so the waits are easy to see"
      ),
      explanation = "It is a sampling interval, not a filter. So a lock profile follows total wait time, the way a CPU profile follows CPU time.",
      doc = "Off-CPU profiling > Lock profiling"
    ),
    ChoiceQuestion(
      text = "Checkouts wait on a hot inventory row in Postgres. Why does api's lock profile show nothing?",
      answer = "A Postgres row lock lives in Postgres: api's thread just waits for the DB to answer, which is not a Java lock",
      others = listOf(
        "Lock profiling only works on pricing",
        "The waits are shorter than 10 ms, so they are filtered out",
        "Row locks are invisible everywhere; only a restart shows them"
      ),
      explanation = "A JVM lock profile covers Java monitors and java.util.concurrent locks. Read lock waits inside Postgres with pg_lock_waiters.",
      doc = "Off-CPU profiling > Lock profiling"
    ),
    ChoiceQuestion(
      text = "api's memory climbs to the container limit, and the leak allocates off heap with Unsafe. Will the allocation profile show the leak?",
      answer = "No: the allocation profile samples Java heap allocations only",
      others = listOf(
        "Yes: every byte the process takes shows in the allocation profile",
        "Yes, but only in the CPU profile",
        "No, because the agent stops when memory is high"
      ),
      explanation = "Memory taken with Unsafe or by native code is not in the heap allocation profile. Container memory against the limit, and the restart count, show this leak.",
      doc = "Off-CPU profiling > What a profile cannot show"
    ),
    ChoiceQuestion(
      text = "Two functions have the same width in a CPU profile. What can you say about how often each one runs?",
      answer = "Nothing: a cheap function called often and a slow function called rarely can have the same width",
      others = listOf(
        "They run the same number of times",
        "The one on the left runs first",
        "The one with the deeper stack runs more often"
      ),
      explanation = "A profile shows total cost, not the call count. Metrics or traces give the count.",
      doc = "Off-CPU profiling > What a profile cannot show"
    ),

    // ---------------------------------------------------------------- Profiles in this lab
    ChoiceQuestion(
      text = "Why does the Pyroscope agent in this lab send JFR, not another format?",
      answer = "JFR is the only output that carries CPU, allocation and lock events together",
      others = listOf(
        "JFR is smaller, so uploads use less network",
        "Grafana can only read JFR",
        "Pyroscope rejects every other format"
      ),
      explanation = "The agent profiles CPU (itimer), allocations (512k) and locks (10ms) at once. async-profiler can only write all three into one JFR recording.",
      doc = "Profiles in this lab > How the agent is wired"
    ),
    ChoiceQuestion(
      text = "You fixed a fault 5 seconds ago, but the last part of the flame graph still shows the old code path. Why?",
      answer = "The agent uploads every 10 seconds, so the newest seconds are not in Pyroscope yet",
      others = listOf(
        "Pyroscope keeps old samples for 1 hour",
        "The fix did not work",
        "Grafana caches profiles for 5 minutes"
      ),
      explanation = "A profile arrives every 10 seconds. Wait for the next upload, then compare a window after the fix with a window before it.",
      doc = "Profiles in this lab > How the agent is wired"
    ),
    ChoiceQuestion(
      text = "You want the code that uses api's CPU. Which Pyroscope query do you start with?",
      answer = "Profile type process_cpu > cpu, label selector {service_name=\"api\"}, the incident window",
      others = listOf(
        "Profile type memory, label selector {service_name=\"api\"}",
        "Profile type mutex > delay, label selector {}",
        "Profile type process_cpu > cpu, label selector {service_name=\"envoy\"}"
      ),
      explanation = "process_cpu is the CPU profile and service_name is the compose service name. Then sort the table by Self and compare with a window before the start.",
      doc = "Profiles in this lab > A good first query"
    ),
    ChoiceQuestion(
      text = "top -H on api shows two carrier threads (ForkJoinPool-...-worker-...) at about 70% each, and every other thread near 0%. What does it point at?",
      answer = "CPU work on the request threads, spread over the carriers: take a CPU profile to name the code",
      others = listOf(
        "A saturated single-threaded event loop",
        "CPU throttling: the container hit its limit",
        "A noisy neighbour on the host"
      ),
      explanation = "A single-threaded bottleneck shows as one thread near 100%. Work spread over the virtual thread carriers comes from the request code itself.",
      doc = "Profiles in this lab > A good first query"
    )
  )
}

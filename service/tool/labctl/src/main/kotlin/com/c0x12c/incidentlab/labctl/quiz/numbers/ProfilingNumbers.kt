package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** Numeric drills for docs/profiling.md. Each generator draws its values and hands them to a pure function a test can call. */
internal object ProfilingNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::samplesPerSecond, ::coresFromSamples, ::functionCores, ::selfShare, ::selfTimeCores, ::offCpuShare, ::lockSamples,
    ::allocSamples, ::carrierBusy
  )

  private fun samplesPerSecond(r: Random): NumericQuestion =
    samplesPerSecond(cores = listOf(0.5, 1.2, 1.5, 3.0).random(r), intervalMs = listOf(5, 10, 20).random(r))

  fun samplesPerSecond(cores: Double, intervalMs: Int): NumericQuestion {
    val answer = cores * MILLIS_PER_SECOND / intervalMs
    return NumericQuestion(
      "A CPU profiler takes one sample per $intervalMs ms of CPU time. The process uses ${shown(cores)} cores. How many samples per second does it record?",
      answer, Tolerance.Relative(0.02), "samples/s",
      "One sample stands for $intervalMs ms of CPU. ${shown(cores)} cores give ${shown(cores * MILLIS_PER_SECOND)} ms of CPU per second, " +
        "so ${shown(cores * MILLIS_PER_SECOND)} / $intervalMs = ${shown(answer)} samples/s.",
      "CPU profiling > Sampling profilers"
    )
  }

  private fun coresFromSamples(r: Random): NumericQuestion =
    coresFromSamples(samples = listOf(900, 1_200, 3_000, 4_500).random(r), seconds = listOf(10, 15, 30).random(r), intervalMs = 10)

  fun coresFromSamples(samples: Int, seconds: Int, intervalMs: Int): NumericQuestion {
    val cpuSeconds = samples * intervalMs / MILLIS_PER_SECOND
    val answer = cpuSeconds / seconds
    return NumericQuestion(
      "A profile of one service holds $samples CPU samples over $seconds seconds. The profiler takes one sample per $intervalMs ms of CPU. How many cores did the service use on average?",
      answer, Tolerance.Relative(0.02), "cores",
      "$samples samples × $intervalMs ms = ${shown(cpuSeconds)} s of CPU. Spread over $seconds s, that is ${shown(cpuSeconds)} / $seconds = ${shown(answer)} cores.",
      "CPU profiling > Sampling profilers"
    )
  }

  private fun functionCores(r: Random): NumericQuestion =
    functionCores(functionSamples = listOf(300, 450, 600, 720).random(r), totalSamples = listOf(1_000, 1_200, 1_500).random(r), processCores = listOf(0.8, 1.4, 1.6).random(r))

  fun functionCores(functionSamples: Int, totalSamples: Int, processCores: Double): NumericQuestion {
    val share = functionSamples.toDouble() / totalSamples
    val answer = share * processCores
    return NumericQuestion(
      "In a CPU flame graph of $totalSamples samples, one function's box covers $functionSamples samples (total time). The process uses ${shown(processCores)} cores. " +
        "About how many cores go to that function and the code it calls?",
      answer, Tolerance.Relative(0.03), "cores",
      "Width is a share of the samples: $functionSamples / $totalSamples = ${shown(share)}. Times ${shown(processCores)} cores = ${shown(answer)} cores. " +
        "Total time includes the callees. Self time would count only its own code.",
      "CPU profiling > Reading a flame graph"
    )
  }

  private fun selfShare(r: Random): NumericQuestion {
    val all = listOf(2_000, 5_000, 10_000).random(r)
    val total = (all * listOf(0.3, 0.5, 0.6).random(r)).toInt()
    val children = (total * listOf(0.25, 0.5, 0.8).random(r)).toInt()
    return selfShare(all = all, total = total, children = children)
  }

  fun selfShare(all: Int, total: Int, children: Int): NumericQuestion {
    val self = total - children
    val answer = self * PERCENT / all
    return NumericQuestion(
      "A flame graph holds $all samples. One function has a total of $total samples. The boxes of its callees add up to $children samples. " +
        "What share of all samples is that function's self time, in %?",
      answer, Tolerance.Relative(0.02), "%",
      "Self = total − callees = $total − $children = $self samples. $self / $all = ${shown(answer)}%. " +
        "A wide box is not always hot itself: most of its width can belong to its callees.",
      "CPU profiling > Reading a flame graph"
    )
  }

  private fun selfTimeCores(r: Random): NumericQuestion =
    selfTimeCores(spanMs = listOf(60, 80, 95).random(r), childrenMs = listOf(30, 40, 50).random(r), rps = listOf(40, 50, 60).random(r))

  fun selfTimeCores(spanMs: Int, childrenMs: Int, rps: Int): NumericQuestion {
    val selfMs = spanMs - childrenMs
    val answer = rps * selfMs / MILLIS_PER_SECOND
    return NumericQuestion(
      "Traces of api show a $spanMs ms server span whose child spans add up to $childrenMs ms. The rest is CPU work in api's own code. " +
        "At $rps requests/s, how many cores does that own code use?",
      answer, Tolerance.Relative(0.02), "cores",
      "Own code = $spanMs − $childrenMs = $selfMs ms per request. $rps × ${shown(selfMs / MILLIS_PER_SECOND)} s = ${shown(answer)} cores. " +
        "The trace says how much and where. A CPU profile of api over the same time range says which function.",
      "CPU profiling > From a trace to a profile"
    )
  }

  private fun offCpuShare(r: Random): NumericQuestion =
    offCpuShare(wallMs = listOf(120, 200, 400, 800).random(r), cpuMs = listOf(4, 8, 15, 30).random(r))

  fun offCpuShare(wallMs: Int, cpuMs: Int): NumericQuestion {
    val answer = (wallMs - cpuMs) * PERCENT / wallMs
    return NumericQuestion(
      "A request takes $wallMs ms from start to end. The thread uses $cpuMs ms of CPU in that time. What share of the request is off-CPU time, in %?",
      answer, Tolerance.Relative(0.01), "%",
      "Off CPU = $wallMs − $cpuMs = ${wallMs - cpuMs} ms. ${wallMs - cpuMs} / $wallMs = ${shown(answer)}%. " +
        "A CPU profile sees only the $cpuMs ms. The rest needs a wall-clock profile, a lock profile or a trace.",
      "Off-CPU profiling > Wall-clock and off-CPU time"
    )
  }

  private fun lockSamples(r: Random): NumericQuestion =
    lockSamples(waitingThreads = listOf(2, 4, 6, 8).random(r), seconds = 10, thresholdMs = listOf(5, 10, 20).random(r))

  fun lockSamples(waitingThreads: Int, seconds: Int, thresholdMs: Int): NumericQuestion {
    val waitMs = waitingThreads * seconds * MILLIS_PER_SECOND
    val answer = waitMs / thresholdMs
    return NumericQuestion(
      "On average $waitingThreads threads wait to enter one lock. The lock profiler takes a sample each time the total lock wait grows by $thresholdMs ms. " +
        "How many lock samples does one $seconds-second upload hold?",
      answer, Tolerance.Relative(0.02), "samples",
      "Total wait = $waitingThreads threads × $seconds s = ${shown(waitMs)} ms. ${shown(waitMs)} / $thresholdMs = ${shown(answer)} samples. " +
        "The setting is a sampling interval, not a filter, so the samples follow total wait time.",
      "Off-CPU profiling > Lock profiling"
    )
  }

  private fun allocSamples(r: Random): NumericQuestion =
    allocSamples(allocMbPerSecond = listOf(50, 120, 200, 400).random(r), intervalKb = listOf(256, 512, 1_024).random(r))

  fun allocSamples(allocMbPerSecond: Int, intervalKb: Int): NumericQuestion {
    val kbPerSecond = allocMbPerSecond * KB_PER_MB
    val answer = kbPerSecond.toDouble() / intervalKb
    return NumericQuestion(
      "A JVM allocates $allocMbPerSecond MB per second on the heap. The allocation profiler takes one sample per $intervalKb KB allocated, on average (1 MB = $KB_PER_MB KB). " +
        "How many allocation samples per second?",
      answer, Tolerance.Relative(0.02), "samples/s",
      "$allocMbPerSecond MB/s × $KB_PER_MB = $kbPerSecond KB/s. $kbPerSecond / $intervalKb = ${shown(answer)} samples/s.",
      "Profiles in this lab > How the agent is wired"
    )
  }

  private fun carrierBusy(r: Random): NumericQuestion =
    carrierBusy(rps = listOf(40, 50, 60).random(r), cpuMs = listOf(10, 15, 20, 25).random(r), carriers = listOf(2, 4).random(r))

  fun carrierBusy(rps: Int, cpuMs: Int, carriers: Int): NumericQuestion {
    val cores = rps * cpuMs / MILLIS_PER_SECOND
    val answer = cores / carriers * PERCENT
    return NumericQuestion(
      "Request code runs on virtual threads, which share $carriers carrier threads. Each request now spends $cpuMs ms of CPU on its virtual thread. " +
        "At $rps requests/s, how busy are the carriers, in %?",
      answer, Tolerance.Relative(0.02), "%",
      "$rps × ${shown(cpuMs / MILLIS_PER_SECOND)} s = ${shown(cores)} cores of work. Spread over $carriers carriers: ${shown(cores)} / $carriers = ${shown(answer)}%. " +
        "Requests now wait for a free carrier, so p99 rises on every route while the event loop stays idle.",
      "Profiles in this lab > How the agent is wired"
    )
  }

  private const val MILLIS_PER_SECOND = 1_000.0
  private const val PERCENT = 100.0
  private const val KB_PER_MB = 1_024
}

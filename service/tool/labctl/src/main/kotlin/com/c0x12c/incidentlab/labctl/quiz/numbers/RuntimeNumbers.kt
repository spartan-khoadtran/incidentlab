package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** Numeric drills for docs/runtime.md: heap sizes, GC pauses, carrier capacity, syscall cost. */
internal object RuntimeNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::oldGenSize, ::pauseShare, ::gcTimeShare, ::fullGcInterval, ::objectLifetime, ::carrierCapacity, ::carrierDemand,
    ::systemShare, ::syscallRate, ::logVolume
  )

  internal fun oldGenSize(r: Random): NumericQuestion {
    val limit = listOf(512, 768, 1024, 2048).random(r)
    val percent = listOf(25, 50, 75).random(r)
    val heap = limit * percent / PERCENT
    val answer = heap * OLD_SHARE
    return NumericQuestion(
      "A JVM runs in a container with a $limit MB memory limit, with -XX:MaxRAMPercentage=$percent and the Serial collector (NewRatio=2, the default). How many MB is the old generation at most?",
      answer, Tolerance.Relative(0.03), "MB",
      "Heap max = $limit × ${shown(percent / PERCENT)} = ${shown(heap)} MB. NewRatio=2 gives the old generation 2/3 of it: ${shown(heap)} × 2/3 = ${shown(answer)} MB. " +
        "The young generation gets the other third.",
      "Garbage collection > Heap and GC in this lab"
    )
  }

  internal fun pauseShare(r: Random): NumericQuestion {
    val pause = listOf(50, 100, 150, 200, 300).random(r)
    val every = listOf(5, 10, 15, 30, 60).random(r)
    val answer = pause / (every * MILLIS_PER_SECOND) * PERCENT
    return NumericQuestion(
      "A full GC pauses the app for $pause ms every $every s. Requests arrive at a steady rate. What share of the requests arrive during a pause, in %?",
      answer, Tolerance.Relative(0.03), "%",
      "Share = pause / time between pauses = $pause / ${shown(every * MILLIS_PER_SECOND)} = ${shown(answer)}%. " +
        "Above about 1%, the pause shows in p99. Below it, look at p99.9 or a latency heatmap.",
      "Garbage collection > GC pauses and tail latency"
    )
  }

  internal fun gcTimeShare(r: Random): NumericQuestion {
    val youngPause = listOf(5, 10, 20).random(r)
    val youngEvery = listOf(2, 5, 10).random(r)
    val fullPause = listOf(100, 200, 400).random(r)
    val fullEvery = listOf(15, 30, 60).random(r)
    val young = youngPause.toDouble() / youngEvery
    val full = fullPause.toDouble() / fullEvery
    val answer = (young + full) / MILLIS_PER_SECOND * PERCENT
    return NumericQuestion(
      "Young GCs pause the app $youngPause ms every $youngEvery s. Full GCs pause it $fullPause ms every $fullEvery s. What share of the time does the app stand still in GC, in %?",
      answer, Tolerance.Relative(0.03), "%",
      "Pause ms per second: $youngPause / $youngEvery + $fullPause / $fullEvery = ${shown(young)} + ${shown(full)} = ${shown(young + full)} ms per 1000 ms, " +
        "so ${shown(answer)}%. In PromQL this is rate(jvm_gc_pause_seconds_sum[5m]).",
      "Garbage collection > GC pauses and tail latency"
    )
  }

  internal fun fullGcInterval(r: Random): NumericQuestion {
    val old = listOf(170, 340, 680).random(r)
    val live = listOf(60, 75, 85, 90).random(r)
    val promotion = listOf(0.5, 1.0, 1.5, 2.0, 4.0).random(r)
    val free = old * (PERCENT - live) / PERCENT
    val answer = free / promotion
    return NumericQuestion(
      "The old generation holds $old MB at most. After a full GC, $live% of it is still live. Objects are promoted at ${shown(promotion)} MB/s. About how many seconds until the next full GC?",
      answer, Tolerance.Relative(0.03), "s",
      "Free room = $old × ${shown((PERCENT - live) / PERCENT)} = ${shown(free)} MB. Time = free room / promotion rate = ${shown(free)} / ${shown(promotion)} = ${shown(answer)} s. " +
        "The higher the live share, the less each full GC frees and the sooner the next one comes.",
      "Garbage collection > Allocation rate and live data"
    )
  }

  internal fun objectLifetime(r: Random): NumericQuestion {
    val store = listOf(40, 80, 120).random(r)
    val churn = listOf(16, 32, 64).random(r)
    val rps = listOf(25, 50, 100).random(r)
    val perSecond = churn * rps / KB_PER_MB
    val answer = store / perSecond
    return NumericQuestion(
      "An in-memory cache holds $store MB. Each request writes $churn KB of new entries over old ones, at $rps requests/s. How many seconds does an entry live on average?",
      answer, Tolerance.Relative(0.03), "s",
      "Write rate = $churn × $rps / 1024 = ${shown(perSecond)} MB/s. Lifetime = size / write rate = $store / ${shown(perSecond)} = ${shown(answer)} s. " +
        "Entries that live this long survive young GCs, get promoted, then die in the old generation.",
      "Garbage collection > Allocation rate and live data"
    )
  }

  internal fun carrierCapacity(r: Random): NumericQuestion {
    val carriers = listOf(2, 4, 8).random(r)
    val pinned = listOf(50, 100, 160, 200, 250).random(r)
    val answer = carriers * MILLIS_PER_SECOND / pinned
    return NumericQuestion(
      "A service has $carriers carrier threads. Every request blocks $pinned ms inside a synchronized block, so its virtual thread stays pinned. How many requests/s can get through at most?",
      answer, Tolerance.Relative(0.02), "requests/s",
      "A pinned request holds one carrier for $pinned ms. $carriers carriers give $carriers × 1000 / $pinned = ${shown(answer)} requests/s. " +
        "CPU stays low: the carriers are blocked, not busy.",
      "Virtual threads > Pinning"
    )
  }

  internal fun carrierDemand(r: Random): NumericQuestion {
    val rps = listOf(10, 15, 20, 30).random(r)
    val pinned = listOf(80, 120, 160, 200).random(r)
    val carriers = listOf(2, 4).random(r)
    val answer = rps * pinned / MILLIS_PER_SECOND / carriers
    return NumericQuestion(
      "Requests arrive at $rps/s. Each one is pinned for $pinned ms. The service has $carriers carriers. What is the demand as a multiple of the carrier capacity?",
      answer, Tolerance.Relative(0.02), "x",
      "Carriers needed = $rps × ${shown(pinned / MILLIS_PER_SECOND)} s = ${shown(rps * pinned / MILLIS_PER_SECOND)}. Divided by $carriers carriers: ${shown(answer)}. " +
        "Above 1 the queue grows until clients time out. Time pinned per second then sits near $carriers.",
      "Virtual threads > Reading the signals"
    )
  }

  internal fun systemShare(r: Random): NumericQuestion {
    val user = listOf(0.1, 0.2, 0.3, 0.6).random(r)
    val system = listOf(0.05, 0.1, 0.2, 0.4).random(r)
    val answer = system / (user + system) * PERCENT
    return NumericQuestion(
      "A container uses ${shown(user)} cores of user time and ${shown(system)} cores of system time. What share of its CPU is system time, in %?",
      answer, Tolerance.Relative(0.03), "%",
      "${shown(system)} / (${shown(user)} + ${shown(system)}) = ${shown(answer)}%. Compare it with the share before the incident: a jump with flat traffic means more syscalls per request.",
      "System time > User and system CPU"
    )
  }

  internal fun syscallRate(r: Random): NumericQuestion {
    val lines = listOf(50, 200, 500, 1000).random(r)
    val rps = listOf(20, 50, 100).random(r)
    val answer = (lines * rps).toDouble()
    return NumericQuestion(
      "A service logs $lines lines per request through a console appender that flushes every line. It serves $rps requests/s. How many write syscalls per second does logging make?",
      answer, Tolerance.Relative(0.01), "syscalls/s",
      "One flushed line is one write: $lines × $rps = ${shown(answer)} syscalls/s, all under the appender's one lock.",
      "System time > Logging costs"
    )
  }

  internal fun logVolume(r: Random): NumericQuestion {
    val lines = listOf(100, 300, 500).random(r)
    val rps = listOf(20, 50, 100).random(r)
    val bytes = listOf(80, 100, 150).random(r)
    val perSecond = lines * rps * bytes.toDouble()
    val answer = perSecond * SECONDS_PER_MINUTE / BYTES_PER_MB
    return NumericQuestion(
      "A service logs $lines lines per request at $rps requests/s. A line is about $bytes bytes. How many MB of log does it write per minute?",
      answer, Tolerance.Relative(0.03), "MB",
      "$lines × $rps × $bytes = ${shown(perSecond)} bytes/s. × 60 / 1048576 = ${shown(answer)} MB per minute. Without rotation, that fills a disk.",
      "System time > Logging costs"
    )
  }

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val OLD_SHARE = 2.0 / 3.0
  private const val KB_PER_MB = 1_024.0
  private const val SECONDS_PER_MINUTE = 60
  private const val BYTES_PER_MB = 1_048_576.0
}

package com.c0x12c.incidentlab.checkout.impl.runtime

import java.time.Duration

/**
 * Runs in its own JVM (see [LegacyOrderCacheTest]) because the virtual thread scheduler reads its
 * parallelism once, when the first virtual thread starts. Prints the wall time in milliseconds of
 * [THREADS] virtual threads that each block for [HOLD], inside the legacy lock or outside it.
 */
object PinningProbe {

  const val THREADS = 20
  val HOLD: Duration = Duration.ofMillis(100)

  @JvmStatic
  fun main(args: Array<String>) {
    val pinned = args.firstOrNull() == "pinned"
    val cache = LegacyOrderCache()
    val start = System.nanoTime()
    val threads = (1..THREADS).map {
      Thread.ofVirtual().start {
        if (pinned) cache.locked { cache.put(HOLD) } else cache.put(HOLD)
      }
    }
    threads.forEach { it.join() }
    println((System.nanoTime() - start) / NANOS_PER_MILLI)
  }

  private const val NANOS_PER_MILLI = 1_000_000L
}

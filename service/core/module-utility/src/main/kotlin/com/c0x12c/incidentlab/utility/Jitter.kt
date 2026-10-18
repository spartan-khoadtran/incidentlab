package com.c0x12c.incidentlab.utility

import java.time.Duration
import kotlin.random.Random

private const val NANOS_PER_MILLI = 1_000_000.0

/** [millis] with a +-[spread] relative jitter, never below zero. */
fun jitter(millis: Double, spread: Double = 0.25, random: Random = Random.Default): Duration {
  val factor = if (spread > 0) 1 + random.nextDouble(-spread, spread) else 1.0
  val nanos = (millis * factor * NANOS_PER_MILLI).toLong().coerceAtLeast(0)
  return Duration.ofNanos(nanos)
}

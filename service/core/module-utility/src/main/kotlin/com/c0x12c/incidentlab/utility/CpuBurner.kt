package com.c0x12c.incidentlab.utility

import java.time.Duration

object CpuBurner {

  /** Busy-loops the calling thread for [duration]. Returns the scratch value so the loop is never optimised away. */
  fun burn(duration: Duration): Long {
    val end = System.nanoTime() + duration.toNanos()
    var x = 0L
    while (System.nanoTime() < end) {
      x = (x * 31 + 7) % MODULUS
    }
    return x
  }

  private const val MODULUS = 1_000_003L
}

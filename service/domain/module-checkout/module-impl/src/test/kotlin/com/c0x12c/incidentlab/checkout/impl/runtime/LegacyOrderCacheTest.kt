package com.c0x12c.incidentlab.checkout.impl.runtime

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isLessThan
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * The mechanism behind the pinning exercise, on JDK 21 with 2 carrier threads (api's 2 cores):
 * 20 virtual threads that sleep 100 ms inside `synchronized` run two at a time, about 1 s in all.
 * The same sleep outside `synchronized` unmounts, so all 20 overlap in about 0.1 s. The JDK does not
 * add carriers to make up for it.
 */
class LegacyOrderCacheTest {

  @Test
  fun `blocking inside the legacy lock caps concurrency at the carrier count`() {
    val pinned = probe("pinned")
    val unpinned = probe("unpinned")

    assertThat(pinned).isBetween(EXPECTED_PINNED_MS * 9 / 10, EXPECTED_PINNED_MS * 3 / 2)
    assertThat(unpinned).isLessThan(EXPECTED_PINNED_MS / 3)
  }

  private fun probe(mode: String): Long {
    val java = ProcessHandle.current().info().command().orElse("java")
    val process = ProcessBuilder(
      java,
      "-D$PARALLELISM=$CARRIERS",
      "-cp",
      System.getProperty("probe.classpath") ?: System.getProperty("java.class.path"),
      PinningProbe::class.java.name,
      mode
    ).redirectErrorStream(true).start()
    check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "probe did not finish" }
    val output = process.inputStream.bufferedReader().readText().trim()
    return output.lines().last().toLongOrNull() ?: error("probe printed: $output")
  }

  private companion object {
    const val PARALLELISM = "jdk.virtualThreadScheduler.parallelism"
    const val CARRIERS = 2
    const val EXPECTED_PINNED_MS = PinningProbe.THREADS / CARRIERS * 100L
    const val TIMEOUT_SECONDS = 60L
  }
}

package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/** Each expected value is worked out by hand in the comment next to it. */
class DiskNumbersTest {

  @Test
  fun `size, throughput and the cap`() {
    // 3000 × 16 KiB = 48000 KiB/s; / 1024 = 46.875 MiB/s
    assertThat(DiskMath.throughputMib(3000, 16)).isCloseTo(46.875, 1e-9)
    // 125 MiB/s × 1024 / 256 KiB = 500 IOPS, below the 3000 IOPS limit
    assertThat(DiskMath.maxIops(3000, 125, 256)).isCloseTo(500.0, 1e-9)
    // 250 × 1024 / 16 = 16000, so the 3000 IOPS limit wins
    assertThat(DiskMath.maxIops(3000, 250, 16)).isCloseTo(3000.0, 1e-9)
  }

  @Test
  fun `utilization, service time, queue and await`() {
    // 200 × 0.002 s = 0.4 → 40%
    assertThat(DiskMath.utilizationPercent(200, 2.0)).isCloseTo(40.0, 1e-9)
    // 0.6 / 300 = 0.002 s → 2 ms
    assertThat(DiskMath.serviceMs(60, 300)).isCloseTo(2.0, 1e-9)
    // 400 × 0.005 s = 2 I/Os
    assertThat(DiskMath.queue(400, 5.0)).isCloseTo(2.0, 1e-9)
    // 1 / (1 − 0.8) = 5 ms
    assertThat(DiskMath.awaitMs(1.0, 80)).isCloseTo(5.0, 1e-9)
    // 3 / 1000 = 0.003 s → 3 ms
    assertThat(DiskMath.awaitFromQueueMs(3.0, 1000)).isCloseTo(3.0, 1e-9)
  }

  @Test
  fun `WAL and checkpoints`() {
    // 60 commits over 40 flushes = 1.5
    assertThat(DiskMath.commitsPerFlush(60, 40)).isCloseTo(1.5, 1e-9)
    // 30/s × 0.001 s = 0.03 processes
    assertThat(DiskMath.meanWaiting(30, 1.0)).isCloseTo(0.03, 1e-9)
    // 1024 / 1.9 = 538.947... MB
    assertThat(DiskMath.requestedCheckpointWalMb(1024, 0.9)).isCloseTo(538.947, 1e-3)
    // 538.95 MB at 0.5 MB/s = 1077.9 s, so the 300 s timeout comes first
    assertThat(DiskMath.checkpointIntervalSeconds(0.5, 1024, 0.9, 300)).isCloseTo(300.0, 1e-9)
    // 2048 / 1.9 = 1077.9 MB at 10 MB/s = 107.79 s, before the 300 s timeout
    assertThat(DiskMath.checkpointIntervalSeconds(10.0, 2048, 0.9, 300)).isCloseTo(107.789, 1e-3)
    // 2560 × 8 KiB = 20 MiB over 30 × 0.1 = 3 s → 6.667 MiB/s
    assertThat(DiskMath.checkpointWriteMibPerSecond(2560, 30, 0.1)).isCloseTo(6.667, 1e-3)
    // 1.8 s / 1800 syncs = 0.001 s → 1 ms
    assertThat(DiskMath.meanSyncMs(1.8, 1800)).isCloseTo(1.0, 1e-9)
  }

  @Test
  fun `limits in the cloud`() {
    // (120 − 80) × 10 s = 400 writes
    assertThat(DiskMath.backlog(120, 80, 10)).isEqualTo(400)
    // max(100, 3 × 20 = 60) = 100, and max(100, 3 × 250) = 750
    assertThat(DiskMath.gp2BaselineIops(20)).isEqualTo(100)
    assertThat(DiskMath.gp2BaselineIops(250)).isEqualTo(750)
  }
}

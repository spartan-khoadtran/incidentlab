package com.c0x12c.incidentlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.c0x12c.incidentlab.labctl.quiz.numbers.NetworkNumbers
import org.junit.jupiter.api.Test

class NetworkNumbersTest {

  @Test
  fun `the network formulas give the answer worked out by hand on fixed values`() {
    // 1 Gbit/s = 125,000,000 B/s; × 0.01 s = 1,250,000 B
    assertThat(NetworkNumbers.bandwidthDelay(gbps = 1.0, rttMs = 10).answer).isCloseTo(1.25, 1e-9)
    // 100 Mbit/s = 12,500,000 B/s; × 0.02 s = 250,000 B; / 1024 = 244.140625 KiB
    assertThat(NetworkNumbers.windowNeeded(mbps = 100, rttMs = 20).answer).isCloseTo(244.140625, 1e-9)
    // 64 × 1024 × 8 = 524,288 bits; / 0.05 s = 10,485,760 bit/s
    assertThat(NetworkNumbers.windowThroughput(windowKib = 64, rttMs = 50).answer).isCloseTo(10.48576, 1e-9)
    // 50 + 20 × 5 = 150 ms; 15 × 0.15 = 2.25
    assertThat(NetworkNumbers.roundTripsPool(rps = 15, holdMs = 50, trips = 20, addedMs = 5).answer).isCloseTo(2.25, 1e-9)
    assertThat(NetworkNumbers.handshakeCost(rttMs = 8, handshakeTrips = 2).answer).isCloseTo(16.0, 1e-9)
    // 1 − 0.99^3 = 1 − 0.970299 = 0.029701
    assertThat(NetworkNumbers.lossTail(lossPercent = 1.0, segments = 3).answer).isCloseTo(2.9701, 1e-9)
    assertThat(NetworkNumbers.lossMean(lossPercent = 2.0).answer).isCloseTo(4.0, 1e-9)
    // 1 + 2 + 4
    assertThat(NetworkNumbers.synBackoff(losses = 3).answer).isCloseTo(7.0, 1e-9)
    assertThat(NetworkNumbers.timeWaitCount(closesPerSecond = 50).answer).isCloseTo(3000.0, 1e-9)
    // 200 × 60 = 12,000 of 28,232 ports
    assertThat(NetworkNumbers.timeWaitPorts(closesPerSecond = 200).answer).isCloseTo(42.5049, 1e-4)
    assertThat(NetworkNumbers.lossShare(retransPerSecond = 12.0, outSegsPerSecond = 4000).answer).isCloseTo(0.3, 1e-9)
  }
}

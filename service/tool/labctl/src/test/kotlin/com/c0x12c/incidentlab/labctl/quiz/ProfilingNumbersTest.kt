package com.c0x12c.incidentlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isCloseTo
import com.c0x12c.incidentlab.labctl.quiz.numbers.ProfilingNumbers
import org.junit.jupiter.api.Test

class ProfilingNumbersTest {

  @Test
  fun `samples follow CPU time`() {
    val perSecond = ProfilingNumbers.samplesPerSecond(cores = 1.5, intervalMs = 10)
    assertThat(perSecond.answer).isCloseTo(150.0, DELTA)
    assertThat(perSecond.explanation).contains("1500 / 10 = 150 samples/s")

    val cores = ProfilingNumbers.coresFromSamples(samples = 1_200, seconds = 10, intervalMs = 10)
    assertThat(cores.answer).isCloseTo(1.2, DELTA)
    assertThat(cores.explanation).contains("12 / 10 = 1.2 cores")
  }

  @Test
  fun `flame graph widths turn into cores and self time`() {
    val function = ProfilingNumbers.functionCores(functionSamples = 600, totalSamples = 1_500, processCores = 1.4)
    assertThat(function.answer).isCloseTo(0.56, DELTA)
    assertThat(function.explanation).contains("600 / 1500 = 0.4")

    val self = ProfilingNumbers.selfShare(all = 5_000, total = 3_000, children = 2_400)
    assertThat(self.answer).isCloseTo(12.0, DELTA)
    assertThat(self.explanation).contains("600 / 5000 = 12%")

    val ownCode = ProfilingNumbers.selfTimeCores(spanMs = 80, childrenMs = 50, rps = 50)
    assertThat(ownCode.answer).isCloseTo(1.5, DELTA)
    assertThat(ownCode.explanation).contains("50 × 0.03 s = 1.5 cores")
  }

  @Test
  fun `off-CPU and lock arithmetic`() {
    val offCpu = ProfilingNumbers.offCpuShare(wallMs = 200, cpuMs = 8)
    assertThat(offCpu.answer).isCloseTo(96.0, DELTA)
    assertThat(offCpu.explanation).contains("192 / 200 = 96%")

    val lock = ProfilingNumbers.lockSamples(waitingThreads = 4, seconds = 10, thresholdMs = 10)
    assertThat(lock.answer).isCloseTo(4_000.0, DELTA)
    assertThat(lock.explanation).contains("40000 / 10 = 4000 samples")
  }

  @Test
  fun `agent settings arithmetic`() {
    val alloc = ProfilingNumbers.allocSamples(allocMbPerSecond = 200, intervalKb = 512)
    assertThat(alloc.answer).isCloseTo(400.0, DELTA)
    assertThat(alloc.explanation).contains("204800 / 512 = 400 samples/s")

    val carriers = ProfilingNumbers.carrierBusy(rps = 50, cpuMs = 25, carriers = 2)
    assertThat(carriers.answer).isCloseTo(62.5, DELTA)
    assertThat(carriers.explanation).contains("1.25 / 2 = 62.5%")
  }

  private companion object {
    const val DELTA = 1e-9
  }
}

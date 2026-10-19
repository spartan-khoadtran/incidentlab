package com.c0x12c.incidentlab.checkout.impl.render

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isBetween
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.management.ManagementFactory
import java.time.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReceiptRendererTest {

  private data class Receipt(val orderId: Long, val total: Double, val items: List<Int>)

  private val receipt = Receipt(orderId = 42, total = 123.45, items = listOf(1, 2, 3))
  private val renderer = ReceiptRenderer(ObjectMapper())
  private val threads = ManagementFactory.getThreadMXBean()

  @BeforeEach
  fun warmUp() {
    renderer.render(receipt, Duration.ZERO)
  }

  @Test
  fun `a platform thread spends about the requested CPU time, measured as CPU time`() {
    val cpuBefore = threads.currentThreadCpuTime
    val wallBefore = System.nanoTime()

    val rendered = renderer.render(receipt, Duration.ofMillis(BUDGET_MS))

    val cpuMillis = (threads.currentThreadCpuTime - cpuBefore) / NANOS_PER_MILLI
    val wallMillis = (System.nanoTime() - wallBefore) / NANOS_PER_MILLI
    assertThat(cpuMillis).isBetween(BUDGET_MS - SLACK_MS, BUDGET_MS * WIDE_TOLERANCE)
    assertThat(wallMillis).isGreaterThanOrEqualTo(cpuMillis - SLACK_MS)
    assertThat(rendered).contains("\"orderId\":42")
    assertThat(rendered).contains("\"items\":[1,2,3]")
  }

  @Test
  fun `a virtual thread has no CPU clock, so the budget is wall time`() {
    var wallMillis = 0L
    var cpuTimeSeen = 0L
    Thread.ofVirtual().start {
      cpuTimeSeen = threads.currentThreadCpuTime
      val before = System.nanoTime()
      renderer.render(receipt, Duration.ofMillis(BUDGET_MS))
      wallMillis = (System.nanoTime() - before) / NANOS_PER_MILLI
    }.join()

    assertThat(cpuTimeSeen).isLessThan(0L)
    assertThat(wallMillis).isBetween(BUDGET_MS - SLACK_MS, BUDGET_MS * WIDE_TOLERANCE)
  }

  @Test
  fun `a zero budget renders once and returns quickly`() {
    val before = System.nanoTime()

    val rendered = renderer.render(receipt, Duration.ZERO)

    assertThat((System.nanoTime() - before) / NANOS_PER_MILLI).isLessThan(BUDGET_MS)
    assertThat(rendered).contains("\"total\":123.45")
  }

  private companion object {
    const val BUDGET_MS = 60L
    const val SLACK_MS = 5L
    const val WIDE_TOLERANCE = 8L
    const val NANOS_PER_MILLI = 1_000_000L
  }
}

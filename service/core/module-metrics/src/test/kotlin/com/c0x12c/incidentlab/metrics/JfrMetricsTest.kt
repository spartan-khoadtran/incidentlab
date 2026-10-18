package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test

class JfrMetricsTest {

  @Test
  fun `a virtual thread sleeping inside synchronized counts as pinned`() {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    JfrMetrics().use { jfr ->
      jfr.bindTo(registry)
      // The stream starts asynchronously; pin a few times so at least some land after it is ready.
      repeat(PINS) {
        Thread.ofVirtual().start {
          synchronized(Any()) { Thread.sleep(PIN_MILLIS) }
        }.join()
      }

      val deadline = System.nanoTime() + TIMEOUT_NANOS
      while (value(registry, "jvm_virtual_thread_pinned_total") < 1.0 && System.nanoTime() < deadline) {
        Thread.sleep(POLL_MILLIS)
      }

      val count = value(registry, "jvm_virtual_thread_pinned_total")
      assertThat(count).isGreaterThanOrEqualTo(1.0)
      assertThat(value(registry, "jvm_virtual_thread_pinned_seconds_total")).isGreaterThanOrEqualTo(count * PIN_MILLIS / MILLIS_PER_SECOND)
    }
  }

  private fun value(registry: PrometheusMeterRegistry, name: String): Double =
    registry.scrape().lines().firstOrNull { it.startsWith("$name ") }?.substringAfter(' ')?.toDouble() ?: 0.0

  private companion object {
    const val PINS = 20
    const val PIN_MILLIS = 20L
    const val POLL_MILLIS = 100L
    const val MILLIS_PER_SECOND = 1_000.0
    const val TIMEOUT_NANOS = 15_000_000_000L
  }
}

package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Runs on the Serial collector (see build.gradle), the one the lab's image uses. */
class JvmMetricsTest {

  private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  private val jvm = JvmMetrics().also { it.bindTo(registry) }

  @AfterEach
  fun close() {
    jvm.close()
  }

  @Test
  fun `heap pools carry the Serial collector names`() {
    val text = registry.scrape()

    assertThat(text).contains("jvm_memory_used_bytes{area=\"heap\",id=\"Eden Space\"}")
    assertThat(text).contains("jvm_memory_used_bytes{area=\"heap\",id=\"Survivor Space\"}")
    assertThat(text).contains("jvm_memory_used_bytes{area=\"heap\",id=\"Tenured Gen\"}")
    assertThat(text).contains("jvm_memory_max_bytes{area=\"heap\",id=\"Tenured Gen\"}")
    assertThat(text).contains("jvm_threads_live_threads")
  }

  @Test
  fun `a full GC lands in the pause histogram and sets the live data`() {
    System.gc()

    val text = waitFor { it.contains("jvm_gc_pause_seconds_bucket{") && liveData(it) > 0.0 }

    assertThat(text).contains("jvm_gc_pause_seconds_bucket{action=\"end of major GC\",cause=\"System.gc()\",gc=\"MarkSweepCompact\",le=\"0.001\"}")
    assertThat(text).contains("le=\"10.0\"")
    assertThat(text).contains("jvm_gc_pause_seconds_count{action=\"end of major GC\",cause=\"System.gc()\",gc=\"MarkSweepCompact\"}")
    assertThat(text).contains("jvm_gc_max_data_size_bytes")
    assertThat(text).contains("jvm_gc_memory_allocated_bytes_total")
    assertThat(text).contains("jvm_gc_memory_promoted_bytes_total")
  }

  @Test
  fun `a young collection is named Copy`() {
    var sink: Any? = null
    val text = waitFor {
      repeat(200) { sink = ByteArray(BLOCK) }
      it.contains("gc=\"Copy\"")
    }

    assertThat(sink != null).isTrue()
    assertThat(text).contains("jvm_gc_pause_seconds_count{action=\"end of minor GC\",cause=\"Allocation Failure\",gc=\"Copy\"}")
  }

  /** GC notifications arrive on a JMX thread, a moment after the collection. */
  private fun waitFor(ready: (String) -> Boolean): String {
    val deadline = System.nanoTime() + TIMEOUT_NANOS
    var text = registry.scrape()
    while (!ready(text) && System.nanoTime() < deadline) {
      Thread.sleep(POLL_MILLIS)
      text = registry.scrape()
    }
    return text
  }

  private fun liveData(text: String): Double =
    text.lines().firstOrNull { it.startsWith("jvm_gc_live_data_size_bytes ") }?.substringAfter(' ')?.toDouble() ?: 0.0

  private companion object {
    const val BLOCK = 1 shl 20
    const val POLL_MILLIS = 50L
    const val TIMEOUT_NANOS = 10_000_000_000L
  }
}

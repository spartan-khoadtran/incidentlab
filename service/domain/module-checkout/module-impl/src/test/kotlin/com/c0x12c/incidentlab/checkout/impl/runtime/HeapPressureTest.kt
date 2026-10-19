package com.c0x12c.incidentlab.checkout.impl.runtime

import assertk.assertThat
import assertk.assertions.contains
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.random.Random
import org.junit.jupiter.api.Test

class HeapPressureTest {

  private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  private val heap = HeapPressure(registry, Random(1))

  @Test
  fun `the store fills to its size and then only replaces`() {
    repeat(10) { heap.write(retainMb = 1, churnKb = 64) }
    assertThat(registry.scrape()).contains("app_retained_bytes 655200.0")

    repeat(10) { heap.write(retainMb = 1, churnKb = 64) }
    assertThat(registry.scrape()).contains("app_retained_bytes 1048560.0")
  }

  @Test
  fun `a healthy request frees the store`() {
    heap.write(retainMb = 1, churnKb = 64)
    heap.write(retainMb = 0, churnKb = 0)

    assertThat(registry.scrape()).contains("app_retained_bytes 0.0")
  }
}

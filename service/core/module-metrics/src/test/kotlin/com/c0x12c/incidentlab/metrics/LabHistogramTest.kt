package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test

class LabHistogramTest {

  private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  private val histogram = LabHistogram(registry, "demo_duration_seconds", "demo", LatencyBuckets.LATENCY, listOf("route"))

  @Test
  fun `a sub-second observation lands in its own bucket`() {
    histogram.observe(0.03, "/checkout")

    val text = registry.scrape()
    assertThat(text).contains("demo_duration_seconds_bucket{route=\"/checkout\",le=\"0.025\"} 0")
    assertThat(text).contains("demo_duration_seconds_bucket{route=\"/checkout\",le=\"0.05\"} 1")
    assertThat(text).contains("demo_duration_seconds_sum{route=\"/checkout\"} 0.03")
  }

  @Test
  fun `an observation above one second keeps its fraction`() {
    histogram.observe(1.2, "/checkout")

    val text = registry.scrape()
    assertThat(text).contains("demo_duration_seconds_bucket{route=\"/checkout\",le=\"1.0\"} 0")
    assertThat(text).contains("demo_duration_seconds_bucket{route=\"/checkout\",le=\"1.5\"} 1")
  }

  @Test
  fun `the exported name keeps a single seconds suffix`() {
    histogram.observe(0.2, "/products")

    assertThat(registry.scrape()).doesNotContain("_seconds_seconds")
  }
}

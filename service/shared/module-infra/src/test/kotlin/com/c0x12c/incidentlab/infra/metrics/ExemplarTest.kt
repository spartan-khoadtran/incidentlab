package com.c0x12c.incidentlab.infra.metrics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.junit.jupiter.api.Test

class ExemplarTest {

  private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry(), Clock.SYSTEM, CurrentSpanContext())
  private val histogram = LabHistogram(registry, "demo_duration_seconds", "demo", LatencyBuckets.LATENCY, listOf("route"))

  @Test
  fun `a sampled span becomes the exemplar of its bucket`() {
    inSpan(TraceFlags.getSampled()) { histogram.observe(0.03, "/checkout") }

    val text = registry.scrape(MetricsController.OPENMETRICS_TEXT)
    assertThat(text).contains("trace_id=\"$TRACE_ID\"")
    assertThat(text).contains("span_id=\"$SPAN_ID\"")
  }

  @Test
  fun `an unsampled span leaves no exemplar`() {
    inSpan(TraceFlags.getDefault()) { histogram.observe(0.03, "/checkout") }

    assertThat(registry.scrape(MetricsController.OPENMETRICS_TEXT)).doesNotContain("trace_id")
  }

  @Test
  fun `the classic text format carries no exemplar`() {
    inSpan(TraceFlags.getSampled()) { histogram.observe(0.03, "/checkout") }

    assertThat(registry.scrape(MetricsController.PROMETHEUS_TEXT)).doesNotContain("trace_id")
  }

  private fun inSpan(flags: TraceFlags, block: () -> Unit) {
    val context = SpanContext.create(TRACE_ID, SPAN_ID, flags, TraceState.getDefault())
    Span.wrap(context).makeCurrent().use { block() }
  }

  private companion object {
    const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    const val SPAN_ID = "00f067aa0ba902b7"
  }
}

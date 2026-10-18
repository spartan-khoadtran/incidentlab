package com.c0x12c.incidentlab.infra.metrics

import io.opentelemetry.api.trace.Span
import io.prometheus.metrics.tracer.common.SpanContext

/**
 * Hands the current OpenTelemetry span to the Prometheus exemplar sampler, so a histogram bucket
 * carries the trace id of one sampled request that landed in it.
 */
class CurrentSpanContext : SpanContext {

  override fun getCurrentTraceId(): String? = current()?.traceId

  override fun getCurrentSpanId(): String? = current()?.spanId

  override fun isCurrentSpanSampled(): Boolean = current()?.isSampled ?: false

  override fun markCurrentSpanAsExemplar() {
    Span.current().setAttribute(SpanContext.EXEMPLAR_ATTRIBUTE_NAME, SpanContext.EXEMPLAR_ATTRIBUTE_VALUE)
  }

  private fun current() = Span.current().spanContext.takeIf { it.isValid }
}

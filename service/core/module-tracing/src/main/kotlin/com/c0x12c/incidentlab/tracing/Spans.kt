package com.c0x12c.incidentlab.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

/**
 * Runs [block] inside a new span that is current for its duration. A thrown exception is recorded
 * on the span and marks it ERROR before it propagates. [parent] overrides the current context,
 * which a Kafka consumer needs to continue the producer's trace.
 */
inline fun <T> Tracer.span(
  name: String,
  kind: SpanKind = SpanKind.INTERNAL,
  attributes: Map<String, Any> = emptyMap(),
  parent: Context? = null,
  block: (Span) -> T
): T {
  val builder = spanBuilder(name).setSpanKind(kind)
  parent?.let { builder.setParent(it) }
  attributes.forEach { (key, value) ->
    when (value) {
      is String -> builder.setAttribute(key, value)
      is Boolean -> builder.setAttribute(key, value)
      is Double -> builder.setAttribute(key, value)
      is Float -> builder.setAttribute(key, value.toDouble())
      is Number -> builder.setAttribute(key, value.toLong())
      else -> builder.setAttribute(key, value.toString())
    }
  }
  val span = builder.startSpan()
  try {
    span.makeCurrent().use {
      return block(span)
    }
  } catch (e: Throwable) {
    span.recordException(e)
    span.setStatus(StatusCode.ERROR, e.message ?: e::class.java.simpleName)
    throw e
  } finally {
    span.end()
  }
}

fun Span.markError(message: String) {
  setStatus(StatusCode.ERROR, message)
}

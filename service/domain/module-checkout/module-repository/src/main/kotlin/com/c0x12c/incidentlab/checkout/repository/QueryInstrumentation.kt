package com.c0x12c.incidentlab.checkout.repository

import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.span
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer

/**
 * One statement as the app sees it: a `db.query` span plus a count and a duration by query name.
 * The duration excludes the pool wait on purpose; that lives in the session span around it, and
 * the gap between the two is one of the things the exercises ask the learner to find.
 */
class QueryInstrumentation(
  registry: MeterRegistry,
  private val tracer: Tracer
) {

  private val queries = LabCounter(registry, "db_client_queries", "Queries sent to Postgres", listOf("query", "outcome"))
  private val duration = LabHistogram(registry, "db_client_duration_seconds", "Query time seen by the app (excludes pool wait)", LatencyBuckets.LATENCY, listOf("query"))

  fun <T> timed(name: String, block: () -> T): T {
    val start = System.nanoTime()
    var outcome = "ok"
    try {
      return tracer.span("db.query $name", SpanKind.CLIENT, mapOf("db.system" to "postgresql", "db.operation" to name)) {
        block()
      }
    } catch (e: Exception) {
      outcome = "error"
      throw e
    } finally {
      queries.increment(name, outcome)
      duration.observe((System.nanoTime() - start) / NANOS_PER_SECOND, name)
    }
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

package com.c0x12c.incidentlab.checkout.impl.db

import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.checkout.impl.resource.ApiGates
import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.markError
import com.c0x12c.incidentlab.tracing.span
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.sql.Connection
import java.time.Duration

class PoolTimeoutException : RuntimeException("pool timeout")

/**
 * A pool checkout as an APM agent would draw it: the `db.session` span and the session histogram
 * cover the wait for a connection plus everything done while holding it. The wait itself is the
 * `db.pool.acquire` child span. Little's Law on the session time is how the pool exercise is solved.
 */
class DbSessions(
  private val db: DatabaseContext,
  private val faults: Faults,
  private val gates: ApiGates,
  private val tracer: Tracer,
  registry: MeterRegistry
) {

  private val session = LabHistogram(
    registry,
    "db_client_session_seconds",
    "From asking the pool for a connection until giving it back (the DB span seen from the app)",
    LatencyBuckets.LATENCY,
    listOf("op")
  )

  fun <T> session(op: String, block: (Connection) -> T): T {
    val start = System.nanoTime()
    try {
      return tracer.span("db.session $op", SpanKind.CLIENT, mapOf("db.system" to "postgresql")) { sessionSpan ->
        val acquired = tracer.span("db.pool.acquire") { acquireSpan ->
          val timeout = Duration.ofMillis((faults.double(CheckoutFaults.POOL_ACQUIRE_TIMEOUT_S) * MILLIS_PER_SECOND).toLong())
          val ok = gates.pool.acquire(timeout)
          gates.poolAcquire.observe((System.nanoTime() - start) / NANOS_PER_SECOND)
          if (!ok) {
            gates.poolTimeouts.increment()
            acquireSpan.markError("pool timeout")
          }
          ok
        }
        if (!acquired) {
          sessionSpan.markError("pool timeout")
          throw PoolTimeoutException()
        }
        try {
          db.withConnection(block)
        } finally {
          gates.pool.release()
        }
      }
    } finally {
      session.observe((System.nanoTime() - start) / NANOS_PER_SECOND, op)
    }
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1_000.0
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

/** Runs [block] in one transaction on this connection: commit on return, rollback on throw. */
fun <T> Connection.transaction(block: () -> T): T {
  autoCommit = false
  try {
    val result = block()
    commit()
    return result
  } catch (e: Exception) {
    rollback()
    throw e
  } finally {
    autoCommit = true
  }
}

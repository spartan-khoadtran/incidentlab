package com.c0x12c.incidentlab.pgobserver.poller

import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.database.query
import com.c0x12c.incidentlab.database.rows
import com.c0x12c.incidentlab.metrics.LabGauge
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One poll of the two views. Query names come from the /* q=name */ comment the services put in front of each statement. */
data class PgSnapshot(
  val up: Boolean,
  val calls: Map<String, Double>,
  val execSeconds: Map<String, Double>,
  val connections: Map<String, Double>,
  val maxConnections: Double,
  val lockWaiters: Double
) {
  companion object {
    val DOWN = PgSnapshot(false, emptyMap(), emptyMap(), emptyMap(), 0.0, 0.0)
  }
}

/**
 * Polls every 2 s and republishes:
 *   pg_query_calls_total{query}         executions counted by Postgres
 *   pg_query_exec_seconds_total{query}  execution time measured by Postgres (includes lock waits,
 *                                       excludes network and the app's pool wait)
 *   pg_connections{state}, pg_max_connections, pg_lock_waiters
 * A query name is registered the first time it shows up, since the catalog is open-ended.
 */
@Singleton
class PgStatsPoller(
  private val db: DatabaseContext,
  private val registry: MeterRegistry
) : ApplicationEventListener<StartupEvent>, Logging {

  private val snapshot = AtomicReference(PgSnapshot.DOWN)
  private val knownQueries = ConcurrentHashMap.newKeySet<String>()
  private val knownStates = ConcurrentHashMap.newKeySet<String>()
  private val connections = LabGauge(registry, "pg_connections", "Backends by state (database lab)", listOf("state"))
  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "pg-poll").apply { isDaemon = true }
  }

  init {
    Gauge.builder("pg_up", snapshot) { if (it.get().up) 1.0 else 0.0 }.description("pgobserver can reach Postgres").register(registry)
    Gauge.builder("pg_max_connections", snapshot) { it.get().maxConnections }.description("max_connections setting").register(registry)
    Gauge.builder("pg_lock_waiters", snapshot) { it.get().lockWaiters }.description("Backends waiting on a lock").register(registry)
  }

  val current: PgSnapshot
    get() = snapshot.get()

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleWithFixedDelay(::poll, 0, POLL_SECONDS, TimeUnit.SECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  private fun poll() {
    val fresh = try {
      db.withConnection { connection ->
        val calls = mutableMapOf<String, Double>()
        val exec = mutableMapOf<String, Double>()
        connection.query(STATEMENTS) { rows ->
          rows.rows { row ->
            val name = QUERY_NAME.find(row.getString("query"))?.groupValues?.get(1) ?: return@rows
            calls.merge(name, row.getDouble("calls"), Double::plus)
            exec.merge(name, row.getDouble("total_exec_time") / MILLIS_PER_SECOND, Double::plus)
          }
        }
        val states = connection.query(STATES) { rows -> rows.rows { it.getString("state") to it.getDouble("n") }.toMap() }
        val waiters = connection.query(LOCK_WAITERS) { rows -> rows.rows { it.getDouble(1) }.firstOrNull() ?: 0.0 }
        val maxConnections = connection.query(MAX_CONNECTIONS) { rows -> rows.rows { it.getString(1).toDouble() }.firstOrNull() ?: 0.0 }
        PgSnapshot(up = true, calls = calls, execSeconds = exec, connections = states, maxConnections = maxConnections, lockWaiters = waiters)
      }
    } catch (e: Exception) {
      warn { "poll failed: ${e.message}" }
      PgSnapshot.DOWN
    }
    snapshot.set(fresh)
    knownStates.addAll(fresh.connections.keys)
    knownStates.forEach { state -> connections.set(fresh.connections[state] ?: 0.0, state) }
    fresh.calls.keys.filter { knownQueries.add(it) }.forEach(::registerQuery)
  }

  private fun registerQuery(name: String) {
    FunctionCounter.builder("pg_query_calls", snapshot) { it.get().calls[name] ?: 0.0 }
      .description("Executions per query (pg_stat_statements)")
      .tag("query", name)
      .register(registry)
    FunctionCounter.builder("pg_query_exec_seconds", snapshot) { it.get().execSeconds[name] ?: 0.0 }
      .description("Execution time per query measured inside Postgres")
      .tag("query", name)
      .register(registry)
  }

  private companion object {
    const val POLL_SECONDS = 2L
    const val MILLIS_PER_SECOND = 1_000.0
    val QUERY_NAME = Regex("/\\*\\s*q=([a-z_]+)\\s*\\*/")
    const val STATEMENTS = "SELECT query, calls, total_exec_time FROM pg_stat_statements " +
      "WHERE query LIKE '%/* q=%' AND query NOT LIKE '%pg_stat_statements%'"
    const val STATES = "SELECT coalesce(state, 'unknown') AS state, count(*) AS n FROM pg_stat_activity " +
      "WHERE datname = current_database() AND pid <> pg_backend_pid() GROUP BY 1"
    const val LOCK_WAITERS = "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
    const val MAX_CONNECTIONS = "SHOW max_connections"
  }
}

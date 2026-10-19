package com.c0x12c.incidentlab.pgobserver.settings

import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.database.query
import com.c0x12c.incidentlab.database.rows
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Makes pgobserver the fault agent for Postgres. Every tick it reads the live settings and, when a
 * fault flag asks for another value, runs ALTER SYSTEM and reloads the server. ALTER SYSTEM needs a
 * superuser and no open transaction: the lab user is the superuser and the pool hands out
 * autocommit connections.
 */
@Singleton
class PgSettingsReconciler(
  private val db: DatabaseContext,
  private val faults: Faults
) : ApplicationEventListener<StartupEvent>, Logging {

  @Volatile
  private var lastError: String? = null

  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "pg-settings").apply { isDaemon = true }
  }

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleWithFixedDelay(::reconcile, 0, TICK_SECONDS, TimeUnit.SECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  fun reconcile() {
    val desired = PgSetting.entries.associateWith { faults.double(it.faultKey) }
    try {
      db.withConnection { connection ->
        val current = connection.query(PgSetting.CURRENT) { rows -> rows.rows { it.getString("name") to it.getString("setting") }.toMap() }
        val statements = PgSetting.plan(current, desired)
        if (statements.isNotEmpty()) {
          connection.createStatement().use { statement ->
            statements.forEach { sql ->
              info { "postgres: $sql" }
              statement.execute(sql)
            }
            statement.execute(PgSetting.RELOAD)
          }
        }
      }
      lastError = null
    } catch (e: Exception) {
      if (e.message != lastError) {
        warn { "settings reconcile failed: ${e.message}" }
      }
      lastError = e.message
    }
  }

  private companion object {
    const val TICK_SECONDS = 3L
  }
}

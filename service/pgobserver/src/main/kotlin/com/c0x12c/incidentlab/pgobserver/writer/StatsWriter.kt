package com.c0x12c.incidentlab.pgobserver.writer

import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.database.execute
import com.c0x12c.incidentlab.database.intArray
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.pgobserver.factory.PgObserverFaults
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
import kotlin.random.Random

/**
 * A steady write job: once a second it rewrites `stats_rows_per_s` random rows of `review_stats`, in
 * one transaction. The rows sit on about 16,400 pages (128 MB, inside Postgres's 256 MB of shared
 * buffers), so the job dirties most of the table between two checkpoints. The fillfactor leaves room
 * on each page, so every update is HOT and the table does not grow.
 */
@Singleton
class StatsWriter(
  private val db: DatabaseContext,
  private val faults: Faults
) : ApplicationEventListener<StartupEvent>, Logging {

  @Volatile
  private var ready = false

  @Volatile
  private var lastError: String? = null

  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "stats-writer").apply { isDaemon = true }
  }

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleWithFixedDelay(::tick, 0, 1, TimeUnit.SECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  private fun tick() {
    try {
      if (!ready) {
        db.withConnection { connection ->
          connection.execute(CREATE)
          val added = connection.execute(FILL)
          if (added > 0) {
            info { "review_stats filled to $ROWS rows ($added added)" }
          }
        }
        ready = true
      }
      val rows = faults.int(PgObserverFaults.STATS_ROWS_PER_S).coerceIn(0, ROWS)
      if (rows > 0) {
        val ids = List(rows) { Random.nextInt(1, ROWS + 1) }
        db.withConnection { connection -> connection.execute(REFRESH) { it.setArray(1, connection.intArray(ids)) } }
      }
      lastError = null
    } catch (e: Exception) {
      if (e.message != lastError) {
        warn { "stats writer failed: ${e.message}" }
      }
      lastError = e.message
    }
  }

  private companion object {
    const val ROWS = 1_280_000
    const val CREATE = "CREATE TABLE IF NOT EXISTS review_stats (id int PRIMARY KEY, score float8 NOT NULL, " +
      "refreshed_at timestamptz NOT NULL DEFAULT now()) WITH (fillfactor = 50)"
    const val FILL = "INSERT INTO review_stats (id, score) " +
      "SELECT g, random() FROM generate_series((SELECT coalesce(max(id), 0) + 1 FROM review_stats), $ROWS) g"
    const val REFRESH = "/* q=refresh_review_stats */ UPDATE review_stats SET score = random(), refreshed_at = now() WHERE id = ANY(?)"
  }
}

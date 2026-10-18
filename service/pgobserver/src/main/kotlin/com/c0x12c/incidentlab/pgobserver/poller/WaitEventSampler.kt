package com.c0x12c.incidentlab.pgobserver.poller

import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.database.query
import com.c0x12c.incidentlab.database.rows
import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A wait event as `pg_stat_activity` names it: `wait_event_type` and `wait_event`. */
data class WaitEvent(val type: String, val event: String)

/**
 * Counts backends per wait event over many samples, then gives the mean per sample. A WAL sync
 * lasts about a millisecond, so one sample every 2 s would almost never catch it.
 */
class WaitEventWindow {
  private val totals = mutableMapOf<WaitEvent, Double>()
  private var samples = 0

  @Synchronized
  fun add(sample: Map<WaitEvent, Int>) {
    samples++
    sample.forEach { (event, backends) -> totals.merge(event, backends.toDouble(), Double::plus) }
  }

  /** The mean number of backends waiting on each event since the last drain, then starts over. */
  @Synchronized
  fun drain(): Map<WaitEvent, Double> {
    val means = if (samples == 0) emptyMap() else totals.mapValues { it.value / samples }
    totals.clear()
    samples = 0
    return means
  }
}

/** Samples `pg_stat_activity` every 100 ms into a [WaitEventWindow] that the poller drains. */
@Singleton
class WaitEventSampler(
  private val db: DatabaseContext
) : ApplicationEventListener<StartupEvent>, Logging {

  val window = WaitEventWindow()

  @Volatile
  private var lastError: String? = null

  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "pg-wait-sampler").apply { isDaemon = true }
  }

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleWithFixedDelay(::sample, 0, SAMPLE_MILLIS, TimeUnit.MILLISECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  private fun sample() {
    try {
      val counts = db.withConnection { connection ->
        connection.query(WAITING) { rows -> rows.rows { WaitEvent(it.getString(1), it.getString(2)) to it.getInt(3) }.toMap() }
      }
      window.add(counts)
      lastError = null
    } catch (e: Exception) {
      if (e.message != lastError) {
        warn { "wait event sample failed: ${e.message}" }
      }
      lastError = e.message
    }
  }

  private companion object {
    const val SAMPLE_MILLIS = 100L

    // Activity and Client are idle waits: a process with nothing to do, or a session waiting for its client.
    const val WAITING = "SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity " +
      "WHERE wait_event IS NOT NULL AND wait_event_type NOT IN ('Activity', 'Client') AND pid <> pg_backend_pid() GROUP BY 1, 2"
  }
}

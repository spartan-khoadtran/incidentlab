package com.c0x12c.incidentlab.pgobserver.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

/** The Postgres settings the lab drives, with the server's own defaults, and the stats writer, off by default. */
object PgObserverFaults {
  const val CHECKPOINT_TIMEOUT_S = "checkpoint_timeout_s"
  const val CHECKPOINT_COMPLETION_TARGET = "checkpoint_completion_target"
  const val MAX_WAL_SIZE_MB = "max_wal_size_mb"
  const val STATS_ROWS_PER_S = "stats_rows_per_s"

  val DEFAULTS: Map<String, Any?> = mapOf(
    CHECKPOINT_TIMEOUT_S to 300,
    CHECKPOINT_COMPLETION_TARGET to 0.9,
    MAX_WAL_SIZE_MB to 1024,
    // rows of review_stats rewritten each second, in one transaction
    STATS_ROWS_PER_S to 0
  )
}

@Factory
class PgObserverFactory {

  @Singleton
  fun faults(): Faults = Faults(PgObserverFaults.DEFAULTS)
}

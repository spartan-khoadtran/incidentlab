package com.c0x12c.incidentlab.pgobserver.settings

import com.c0x12c.incidentlab.pgobserver.factory.PgObserverFaults
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One Postgres setting the lab drives: its name, the fault key that holds the wanted value, and how
 * the value is written in SQL. All three reload on SIGHUP, so `pg_reload_conf()` is enough.
 */
enum class PgSetting(val setting: String, val faultKey: String, private val whole: Boolean, private val literal: (Double) -> String) {
  CHECKPOINT_TIMEOUT("checkpoint_timeout", PgObserverFaults.CHECKPOINT_TIMEOUT_S, true, { "'${it.roundToLong()}s'" }),
  CHECKPOINT_COMPLETION_TARGET("checkpoint_completion_target", PgObserverFaults.CHECKPOINT_COMPLETION_TARGET, false, { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }),
  MAX_WAL_SIZE("max_wal_size", PgObserverFaults.MAX_WAL_SIZE_MB, true, { "'${it.roundToLong()}MB'" });

  /** The value Postgres ends up with: integer settings drop the fraction. */
  fun stored(value: Double): Double = if (whole) value.roundToLong().toDouble() else value

  fun alter(value: Double): String = "ALTER SYSTEM SET $setting = ${literal(stored(value))}"

  companion object {
    /** `pg_settings.setting` prints every value in its base unit (seconds, MB, a fraction), so it compares with the fault value as a number. */
    const val CURRENT = "SELECT name, setting FROM pg_settings WHERE name IN ('checkpoint_timeout', 'checkpoint_completion_target', 'max_wal_size')"
    const val RELOAD = "SELECT pg_reload_conf()"
    private const val EPSILON = 1e-9

    /** The ALTER SYSTEM statements that take `current` (setting by name) to `desired`; empty when nothing differs. */
    fun plan(current: Map<String, String>, desired: Map<PgSetting, Double>): List<String> =
      desired.filter { (setting, wanted) ->
        val now = current[setting.setting]?.toDoubleOrNull()
        now == null || abs(now - setting.stored(wanted)) > EPSILON
      }.map { (setting, wanted) -> setting.alter(wanted) }
  }
}

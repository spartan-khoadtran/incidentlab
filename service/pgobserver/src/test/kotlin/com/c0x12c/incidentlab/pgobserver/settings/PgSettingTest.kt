package com.c0x12c.incidentlab.pgobserver.settings

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.pgobserver.factory.PgObserverFaults
import org.junit.jupiter.api.Test

class PgSettingTest {

  private val healthy = mapOf("checkpoint_timeout" to "300", "checkpoint_completion_target" to "0.9", "max_wal_size" to "1024")

  @Test
  fun `the healthy defaults match a stock server, so nothing is altered`() {
    val faults = Faults(PgObserverFaults.DEFAULTS)
    val desired = PgSetting.entries.associateWith { faults.double(it.faultKey) }

    assertThat(PgSetting.plan(healthy, desired)).isEmpty()
  }

  @Test
  fun `a fault override renders one ALTER SYSTEM per setting that differs, in the unit Postgres expects`() {
    val faults = Faults(PgObserverFaults.DEFAULTS)
    faults.replace(mapOf("checkpoint_timeout_s" to 30, "checkpoint_completion_target" to 0.1, "max_wal_size_mb" to 64))
    val desired = PgSetting.entries.associateWith { faults.double(it.faultKey) }

    assertThat(PgSetting.plan(healthy, desired)).containsExactly(
      "ALTER SYSTEM SET checkpoint_timeout = '30s'",
      "ALTER SYSTEM SET checkpoint_completion_target = 0.1",
      "ALTER SYSTEM SET max_wal_size = '64MB'"
    )
  }

  @Test
  fun `only the settings that differ are altered, and a missing current value counts as different`() {
    val desired = mapOf(PgSetting.CHECKPOINT_TIMEOUT to 300.0, PgSetting.CHECKPOINT_COMPLETION_TARGET to 0.9, PgSetting.MAX_WAL_SIZE to 2048.0)

    assertThat(PgSetting.plan(healthy, desired)).containsExactly("ALTER SYSTEM SET max_wal_size = '2048MB'")
    assertThat(PgSetting.plan(healthy - "checkpoint_timeout", desired)).containsExactly(
      "ALTER SYSTEM SET checkpoint_timeout = '300s'",
      "ALTER SYSTEM SET max_wal_size = '2048MB'"
    )
  }

  @Test
  fun `the completion target is written as a plain decimal`() {
    assertThat(PgSetting.CHECKPOINT_COMPLETION_TARGET.alter(0.9)).isEqualTo("ALTER SYSTEM SET checkpoint_completion_target = 0.9")
    assertThat(PgSetting.CHECKPOINT_COMPLETION_TARGET.alter(1.0)).isEqualTo("ALTER SYSTEM SET checkpoint_completion_target = 1")
  }
}

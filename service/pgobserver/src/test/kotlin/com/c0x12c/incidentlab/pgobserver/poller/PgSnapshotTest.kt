package com.c0x12c.incidentlab.pgobserver.poller

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import org.junit.jupiter.api.Test

class PgSnapshotTest {

  @Test
  fun `a failed poll keeps the counters, so rate() does not read a reset, and clears the gauges`() {
    val last = PgSnapshot(
      up = true,
      calls = mapOf("checkout" to 120.0),
      execSeconds = mapOf("checkout" to 3.5),
      connections = mapOf("active" to 2.0),
      maxConnections = 100.0,
      lockWaiters = 1.0,
      checkpoints = CheckpointStats(timed = 7.0),
      wal = WalStats(bytes = 4096.0)
    )

    val down = last.down()

    assertThat(down.up).isFalse()
    assertThat(down.calls).isEqualTo(last.calls)
    assertThat(down.execSeconds).isEqualTo(last.execSeconds)
    assertThat(down.checkpoints).isEqualTo(last.checkpoints)
    assertThat(down.wal).isEqualTo(last.wal)
    assertThat(down.connections).isEmpty()
    assertThat(down.maxConnections).isEqualTo(0.0)
    assertThat(down.lockWaiters).isEqualTo(0.0)
  }
}

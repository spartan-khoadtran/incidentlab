package com.c0x12c.incidentlab.faults

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class FaultsTest {

  private val faults = Faults(mapOf("pool_max" to 4, "hot_row" to false, "tenants" to mapOf("web" to 20)))

  @Test
  fun `an override wins over the default and a null clears it`() {
    faults.replace(mapOf("pool_max" to 1))
    assertThat(faults.int("pool_max")).isEqualTo(1)

    faults.replace(mapOf("pool_max" to null))
    assertThat(faults.int("pool_max")).isEqualTo(4)
  }

  @Test
  fun `the version moves only on an effective change`() {
    faults.replace(mapOf("hot_row" to true))
    val after = faults.version

    faults.replace(mapOf("hot_row" to true))
    assertThat(faults.version).isEqualTo(after)

    faults.reset()
    assertThat(faults.version).isEqualTo(after + 1)
    assertThat(faults.bool("hot_row")).isFalse()
  }

  @Test
  fun `typed readers coerce what JSON hands over`() {
    faults.replace(mapOf("pool_max" to 2.0, "hot_row" to 1, "leak_kb" to "160"))

    assertThat(faults.int("pool_max")).isEqualTo(2)
    assertThat(faults.bool("hot_row")).isTrue()
    assertThat(faults.double("leak_kb")).isEqualTo(160.0)
    assertThat(faults.map("tenants")).isEqualTo(mapOf("web" to 20))
    assertThat(faults.int("missing")).isEqualTo(0)
  }
}

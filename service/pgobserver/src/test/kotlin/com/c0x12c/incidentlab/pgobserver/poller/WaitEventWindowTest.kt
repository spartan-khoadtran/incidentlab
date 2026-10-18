package com.c0x12c.incidentlab.pgobserver.poller

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WaitEventWindowTest {

  private val walSync = WaitEvent("IO", "WALSync")
  private val lock = WaitEvent("Lock", "transactionid")

  @Test
  fun `the mean counts a sample where nobody waited as zero`() {
    val window = WaitEventWindow()
    window.add(mapOf(walSync to 2))
    window.add(mapOf(walSync to 1, lock to 3))
    window.add(emptyMap())
    window.add(emptyMap())

    assertThat(window.drain()).isEqualTo(mapOf(walSync to 0.75, lock to 0.75))
  }

  @Test
  fun `a drain starts a new window`() {
    val window = WaitEventWindow()
    window.add(mapOf(walSync to 4))
    window.drain()

    assertThat(window.drain()).isEmpty()
    window.add(mapOf(lock to 1))
    assertThat(window.drain()).isEqualTo(mapOf(lock to 1.0))
  }
}

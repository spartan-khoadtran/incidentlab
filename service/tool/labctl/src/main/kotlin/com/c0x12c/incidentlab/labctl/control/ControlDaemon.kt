package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.Duration

/**
 * Keeps the lab in the desired fault state. Every tick it recomputes the desired state from the
 * state file and hands it to each applier, so the state file is the whole truth and a hand-made
 * change lasts one tick. A service that is restarting is simply tried again next tick.
 */
class ControlDaemon(
  private val store: StateStore,
  private val appliers: List<Applier>,
  private val interval: Duration = Duration.ofSeconds(3)
) : Logging {

  fun run() {
    info { "lab controller started" }
    while (!Thread.currentThread().isInterrupted) {
      try {
        applyOnce()
      } catch (e: Exception) {
        warn { "control: ${e.message}" }
      }
      Thread.sleep(interval.toMillis())
    }
  }

  fun applyOnce() {
    val desired = DesiredState.of(store.load())
    appliers.forEach { it.apply(desired) }
  }
}

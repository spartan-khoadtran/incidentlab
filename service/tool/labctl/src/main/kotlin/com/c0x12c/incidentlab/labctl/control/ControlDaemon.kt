package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.Duration

/**
 * Keeps the lab in the desired fault state. Every tick it recomputes the desired state from the
 * state file and hands it to each applier, so the state file is the whole truth and a hand-made
 * change lasts one tick. A service that is restarting is simply tried again next tick. Each
 * applier and the [cleanup] of queued Grafana marks run on their own thread at that pace, so a
 * hung Docker engine or Grafana never delays the service faults.
 */
class ControlDaemon(
  private val store: StateStore,
  private val appliers: List<Applier>,
  private val capabilities: CapabilitiesFile? = null,
  private val interval: Duration = TICK,
  private val cleanup: AnnotationCleanup? = null
) : Logging {

  /** Tells the CLI and the API which container settings this daemon applies, so they never start an exercise it cannot run. */
  fun publishCapabilities() {
    val keys = appliers.flatMap { it.dockerKeys }.toSet()
    capabilities?.write(keys)
    info { "container settings applied: ${keys.sorted().joinToString().ifEmpty { "none" }}" }
  }

  fun run() {
    info { "lab controller started" }
    cleanup?.let { pass -> Thread.ofVirtual().name("annotation-cleanup").start { repeatEveryTick("annotations") { pass.runOnce() } } }
    appliers.map { applier ->
      val name = applier::class.simpleName ?: "applier"
      Thread.ofVirtual().name(name).start { repeatEveryTick(name) { applier.apply(DesiredState.of(store.load())) } }
    }.forEach { it.join() }
  }

  private fun repeatEveryTick(name: String, step: () -> Unit) {
    while (!Thread.currentThread().isInterrupted) {
      try {
        step()
      } catch (e: Exception) {
        warn { "$name: ${e.message}" }
      }
      Thread.sleep(interval.toMillis())
    }
  }

  fun applyOnce() {
    val desired = DesiredState.of(store.load())
    appliers.forEach { it.apply(desired) }
  }

  companion object {
    val TICK: Duration = Duration.ofSeconds(3)
  }
}

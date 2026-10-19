package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Keeps the lab in the desired fault state. Every tick it recomputes the desired state from the
 * state file and hands it to each applier, so the state file is the whole truth and a hand-made
 * change lasts one tick. A service that is restarting is simply tried again next tick. Each
 * applier and the [cleanup] of queued Grafana marks run on their own thread at that pace, so a
 * hung Docker engine or Grafana never delays the service faults. Appliers found later join with [add].
 */
class ControlDaemon(
  private val store: StateStore,
  appliers: List<Applier>,
  private val capabilities: CapabilitiesFile? = null,
  private val interval: Duration = TICK,
  private val cleanup: AnnotationCleanup? = null,
  private val onFatal: (Throwable) -> Unit = { exitProcess(1) }
) : Logging {

  private val appliers = CopyOnWriteArrayList(appliers)
  private val stopped = CountDownLatch(1)

  @Volatile
  private var running = false

  /** Tells the CLI and the API which container settings this daemon applies, so they never start an exercise it cannot run. */
  fun publishCapabilities() {
    val keys = appliers.flatMap { it.dockerKeys }.toSet()
    capabilities?.write(keys)
    info { "container settings applied: ${keys.sorted().joinToString().ifEmpty { "none" }}" }
  }

  /** Appliers found after the start, such as the container appliers once Docker answers. */
  @Synchronized
  fun add(more: List<Applier>) {
    appliers += more
    publishCapabilities()
    if (running) more.forEach(::launch)
  }

  /** Starts every loop and blocks until one of them hits an error the daemon cannot recover from. */
  fun run() {
    info { "lab controller started" }
    synchronized(this) {
      running = true
      cleanup?.let { pass -> loop("annotation-cleanup") { pass.runOnce() } }
      appliers.forEach(::launch)
    }
    stopped.await()
  }

  private fun launch(applier: Applier) {
    loop(applier::class.simpleName ?: "applier") { applier.apply(DesiredState.of(store.load())) }
  }

  private fun loop(name: String, step: () -> Unit) {
    Thread.ofVirtual().name(name).start {
      try {
        while (!Thread.currentThread().isInterrupted) {
          try {
            step()
          } catch (e: Exception) {
            warn { "$name: ${e.message}" }
          }
          Thread.sleep(interval.toMillis())
        }
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      } catch (e: Throwable) {
        warn(e) { "$name stopped: the controller exits so compose restarts it" }
        stopped.countDown()
        onFatal(e)
      }
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

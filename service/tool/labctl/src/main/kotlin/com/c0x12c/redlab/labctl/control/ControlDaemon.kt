package com.c0x12c.redlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.redlab.labctl.state.StateStore
import java.time.Duration

/**
 * Keeps every service in the desired fault state. Every tick it recomputes the desired overrides
 * from the state file and PUTs them to each service, so the state file is the whole truth and a
 * hand-made PUT lasts one tick. A service that is restarting is simply tried again next tick.
 */
class ControlDaemon(
  private val store: StateStore,
  private val http: JsonHttp,
  private val docker: DockerControl?,
  private val interval: Duration = Duration.ofSeconds(3)
) : Logging {

  fun run() {
    info { "lab controller started" }
    if (docker == null) {
      info { "docker socket not found: CPU-limit exercises are disabled" }
    }
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
    for ((service, overrides) in desired.faults) {
      val url = Services.ENDPOINTS[service] ?: continue
      runCatching { http.put("$url/admin/faults", overrides) }
    }
    if (docker != null) {
      for ((container, settings) in desired.docker) {
        val cpus = (settings["cpus"] as? Number)?.toDouble() ?: continue
        try {
          docker.ensureCpus(container, cpus)
        } catch (e: Exception) {
          warn { "docker: ${e.message}" }
        }
      }
    }
  }
}

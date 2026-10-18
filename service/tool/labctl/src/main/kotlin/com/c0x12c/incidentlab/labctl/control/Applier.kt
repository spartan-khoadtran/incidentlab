package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn

/** One thing the daemon pushes to the lab every tick, from the desired state. */
fun interface Applier {
  fun apply(desired: Desired)
}

/** PUTs each service's overrides to its admin endpoint. A service that does not answer is tried again next tick. */
class ServiceFaultApplier(private val http: JsonHttp) : Applier {

  override fun apply(desired: Desired) {
    for ((service, overrides) in desired.faults) {
      val url = Services.ENDPOINTS[service] ?: continue
      runCatching { http.put("$url/admin/faults", overrides) }
    }
  }
}

/** Sets each container's CPU limit through the Docker socket. */
class ContainerCpuApplier(private val docker: DockerControl) : Applier, Logging {

  override fun apply(desired: Desired) {
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

package com.c0x12c.redlab.labctl.control

import com.c0x12c.redlab.labctl.scenario.Params
import com.c0x12c.redlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.redlab.labctl.state.LabState

/** Every service's overrides and every container's settings, computed from the state file alone. */
data class Desired(
  val faults: Map<String, Map<String, Any?>>,
  val docker: Map<String, Map<String, Any?>>
)

object DesiredState {

  fun of(state: LabState, catalog: ScenarioCatalog = ScenarioCatalog): Desired {
    val faults = Services.ENDPOINTS.keys.associateWith { mutableMapOf<String, Any?>() }.toMutableMap()
    for ((service, overrides) in state.base) {
      faults.getOrPut(service) { mutableMapOf() }.putAll(overrides)
    }
    val docker = Services.BASE_DOCKER.mapValues { it.value.toMutableMap() }.toMutableMap()
    val active = state.active
    val scenario = active?.let { catalog.byId[it.id] }
    val spec = if (active != null && scenario != null) runCatching { scenario.faults(Params(active.params, startedAt = "")) }.getOrNull() else null
    if (spec != null) {
      for ((service, overrides) in spec.services) {
        faults.getOrPut(service) { mutableMapOf() }.putAll(overrides)
      }
      for ((container, settings) in spec.docker) {
        docker.getOrPut(container) { mutableMapOf() }.putAll(settings)
      }
    }
    return Desired(faults = faults, docker = docker)
  }
}

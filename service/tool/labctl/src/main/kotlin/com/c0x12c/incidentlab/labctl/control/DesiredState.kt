package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.ramp.RampCatalog
import com.c0x12c.incidentlab.labctl.scenario.FaultSource
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState

/** Every service's overrides and every container's settings, computed from the state file alone. */
data class Desired(
  val faults: Map<String, Map<String, Any?>>,
  val docker: Map<String, Map<String, Any?>>
)

object DesiredState {

  /** The first source that owns the active id wins, so an id must be unique across them. */
  val SOURCES: List<FaultSource> = listOf(ScenarioCatalog, RampCatalog)

  fun of(state: LabState, sources: List<FaultSource> = SOURCES, now: Double = System.currentTimeMillis() / MILLIS_PER_SECOND): Desired {
    val faults = Services.ENDPOINTS.keys.associateWith { mutableMapOf<String, Any?>() }.toMutableMap()
    for ((service, overrides) in state.base) {
      faults.getOrPut(service) { mutableMapOf() }.putAll(overrides)
    }
    val docker = Services.BASE_DOCKER.mapValues { it.value.toMutableMap() }.toMutableMap()
    val spec = state.active?.let { lookup(it, sources, now) }
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

  /** An id nobody owns, or params an older catalog wrote that no longer render, mean no fault rather than a crash. */
  private fun lookup(active: ActiveExercise, sources: List<FaultSource>, now: Double): FaultSpec? {
    val params = Params(active.params, startedAt = "", elapsedSeconds = now - active.started)
    return sources.firstNotNullOfOrNull { source -> runCatching { source.faults(active.id, params) }.getOrNull() }
  }

  private const val MILLIS_PER_SECOND = 1_000.0
}

package com.c0x12c.incidentlab.labctl.bench

import com.c0x12c.incidentlab.labctl.scenario.FaultSource
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params

/** `make bench`: loadgen switches to closed-loop clients for a fixed time, then back to its open load. */
object BenchCatalog : FaultSource {

  const val ID = "bench"
  const val DEFAULT_CLIENTS = 20
  const val DEFAULT_SECONDS = 120
  const val MIN_CLIENTS = 1
  const val MAX_CLIENTS = 200
  const val MIN_SECONDS = 30
  const val MAX_SECONDS = 600

  /** The first seconds of the bench stay out of its window, so the switch from the open load does not leak into it. */
  const val SETTLE_SECONDS = 10

  /** The window before the bench that it is compared with. */
  const val BEFORE_SECONDS = 120

  fun params(clients: Int, seconds: Int): Map<String, Any> = mapOf("clients" to clients, "seconds" to seconds)

  override fun faults(id: String, params: Params): FaultSpec? {
    if (id != ID) return null
    if (!running(params)) return FaultSpec()
    return FaultSpec(services = mapOf("loadgen" to mapOf("closed_clients" to params.int("clients"))))
  }

  fun running(params: Params): Boolean = params.elapsedSeconds >= 0 && params.elapsedSeconds < params.int("seconds")

  fun title(params: Params): String = "Closed-loop bench (${params.int("clients")} clients, ${params.int("seconds")} s)"
}

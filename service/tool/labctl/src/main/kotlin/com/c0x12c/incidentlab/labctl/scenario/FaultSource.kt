package com.c0x12c.incidentlab.labctl.scenario

/**
 * Where the daemon finds the faults of the active exercise by its id. The incident catalog is one
 * source; the capacity ramp and the closed-loop bench are others, so every mode drives the lab
 * through the same state file.
 */
fun interface FaultSource {
  /** Null when this source does not own the id. */
  fun faults(id: String, params: Params): FaultSpec?
}

package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.state.LabState

/** An exercise kind other than the incident. It owns the learner commands while its mode is the active one. */
interface ExerciseMode {
  val mode: String

  fun answer(state: LabState): Int

  fun reveal(state: LabState): Int

  fun hint(state: LabState): Int

  /** The lines about the active exercise only; the caller adds the service status. */
  fun status(state: LabState)
}

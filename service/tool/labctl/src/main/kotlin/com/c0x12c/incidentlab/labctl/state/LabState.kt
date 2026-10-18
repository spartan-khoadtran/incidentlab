package com.c0x12c.incidentlab.labctl.state

import java.util.UUID

data class ActiveExercise(
  val id: String,
  /** The kind of exercise; "incident" is the dashboard investigation. */
  val mode: String = "incident",
  val params: Map<String, Any>,
  /** Epoch seconds. */
  val started: Double,
  val hints: Int = 0,
  val n: Int,
  val answered: Boolean = false,
  /** The four picks of an answered incident, so its result can be shown again. */
  val choices: Choices? = null,
  /** What the learner said about a ramp, and how many steps had run. */
  val rampAnswer: RampSubmission? = null,
  /** Names this exercise for a command that reads it first and writes later. The API shows it, so an older file's default never holds the scenario id. */
  val key: String = "legacy-$n-$started",
  /** Annotations written ahead of time, so an early end can delete the ones still in the future. */
  val annotations: List<PlannedAnnotation> = emptyList()
) {
  companion object {
    fun newKey(): String = UUID.randomUUID().toString()
  }
}

/** False when [key] names another exercise than this one, or this one is gone. A null key matches any exercise. */
fun ActiveExercise?.matches(key: String?): Boolean = key == null || this?.key == key

/** A Grafana annotation by its id, and its time in epoch seconds. */
data class PlannedAnnotation(
  val id: Long,
  val at: Double
)

data class Choices(
  val nature: String,
  val location: String,
  val signal: String,
  val cause: String
)

data class RampSubmission(
  val kneeRps: Double,
  val maxRps: Double,
  val resource: String,
  val stepsRun: Int
)

data class HistoryEntry(
  val n: Int,
  val id: String,
  val mode: String = "incident",
  val title: String,
  val level: Int,
  /** Null for a revealed exercise, which is not scored. */
  val score: Double?,
  val hints: Int,
  val minutes: Double,
  /** Epoch seconds. */
  val at: Double,
  /** The questions answered wrong: nature, location, signal, cause; a half-right cause is "cause (partial)". */
  val missed: List<String> = emptyList(),
  /** The learner's own one-line note on the evidence they used. */
  val note: String = ""
)

/** The whole lab state. `base` holds overrides that outlive an exercise, such as the load multiplier. */
data class LabState(
  val active: ActiveExercise? = null,
  val history: List<HistoryEntry> = emptyList(),
  val base: Map<String, Map<String, Any?>> = emptyMap()
)

/** The state with [note] on the latest history entry numbered [n]. An empty note changes nothing. */
fun LabState.withNote(n: Int, note: String): LabState {
  val index = history.indexOfLast { it.n == n }
  if (note.isEmpty() || index < 0) return this
  return copy(history = history.toMutableList().also { it[index] = it[index].copy(note = note) })
}

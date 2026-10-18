package com.c0x12c.incidentlab.labctl.state

data class ActiveExercise(
  val id: String,
  /** The kind of exercise; "incident" is the dashboard investigation. */
  val mode: String = "incident",
  val params: Map<String, Any>,
  /** Epoch seconds. */
  val started: Double,
  val hints: Int = 0,
  val n: Int,
  val answered: Boolean = false
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

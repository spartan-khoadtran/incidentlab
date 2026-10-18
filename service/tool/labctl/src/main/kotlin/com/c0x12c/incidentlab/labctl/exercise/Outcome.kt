package com.c0x12c.incidentlab.labctl.exercise

/** What an engine call gives back: its value, or the reason it did nothing. The CLI and the API each word the reason their own way. */
sealed interface Outcome<out T> {
  data class Done<T>(val value: T) : Outcome<T>

  data class Refused(val refusal: Refusal) : Outcome<Nothing>
}

sealed interface Refusal {
  data object ExerciseOpen : Refusal

  data object NoExercise : Refusal

  /** Started by an older catalog, so its params no longer render. */
  data class Stale(val n: Int) : Refusal

  /** The open exercise belongs to another mode, which owns this command. */
  data class WrongMode(val mode: String, val n: Int) : Refusal

  data object AlreadyAnswered : Refusal

  data object NoHintsLeft : Refusal

  data class UnknownId(val id: String) : Refusal

  /** Toolbox mode needs a scenario with CLI proof. */
  data class NoCli(val id: String) : Refusal

  /** No scenario matches the level and topic. */
  data object NoMatch : Refusal

  /**
   * The controller cannot apply container settings the scenario needs. [id] is null when every
   * scenario that matched was left out, so a random pick names no scenario.
   */
  data class Unsupported(val id: String?, val missing: Set<String>) : Refusal

  data class UnknownKey(val question: String, val key: String) : Refusal

  /** The ramp's steps, and the draws of a `baseLoad` scenario, assume the default load. */
  data class LoadNotOne(val multiplier: Double) : Refusal

  /** The ramp's last step has not ended. */
  data class TooEarly(val secondsLeft: Int) : Refusal

  data object PrometheusDown : Refusal

  /** The bench has not ended. */
  data class StillRunning(val secondsLeft: Int) : Refusal

  /** The quiz session expired or never existed. */
  data object UnknownSession : Refusal

  data class UnknownQuestion(val index: Int) : Refusal

  /** An answer of the wrong kind: text for a number question, or an option the question does not have. */
  data class BadAnswer(val reason: String) : Refusal
}

package com.c0x12c.redlab.labctl.quiz

/** Either a relative tolerance (0.03 = 3%) or an absolute one. */
sealed class Tolerance {
  data class Relative(val share: Double) : Tolerance()

  data class Absolute(val amount: Double) : Tolerance()
}

/** One question of `make quiz`. Every drill points at the heading of the guide it comes from. */
sealed interface Drill {
  val text: String
  val explanation: String
  val doc: String
}

/** Answered with a number. The generators in [Numbers] draw new values every run. */
data class NumericQuestion(
  override val text: String,
  val answer: Double,
  val tolerance: Tolerance,
  val unit: String,
  override val explanation: String,
  override val doc: String
) : Drill

/** Answered by picking one option. The options are shuffled every run, so a position is never the answer. */
data class ChoiceQuestion(
  override val text: String,
  val answer: String,
  val others: List<String>,
  override val explanation: String,
  override val doc: String
) : Drill {
  val options: List<String> get() = listOf(answer) + others
}

enum class QuizKind(val flag: String) {
  NUMBERS("numbers"),
  CONCEPTS("concepts"),
  ALL("all")
}

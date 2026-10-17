package com.c0x12c.redlab.labctl.quiz

import com.c0x12c.redlab.labctl.Terminal
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.random.Random

/** Drills for the guide: numbers (fresh values every run) and concepts (shuffled multiple choice). */
object Quiz {

  fun run(count: Int, kind: QuizKind, terminal: Terminal, random: Random = Random.Default): Int {
    val drills = draw(count, kind, random)
    var score = 0
    drills.forEachIndexed { index, drill ->
      terminal.out()
      terminal.out("Question ${index + 1}/${drills.size}. ${drill.text}")
      val correct = when (drill) {
        is NumericQuestion -> askNumeric(drill, terminal)
        is ChoiceQuestion -> askChoice(drill, terminal, random)
      } ?: return inputClosed(terminal, score)
      if (correct) score += 1
      terminal.out("  ${drill.explanation}")
      terminal.out("  Read again in $GUIDE: ${drill.doc}")
    }
    terminal.out()
    terminal.out("Result: $score/${drills.size}")
    return score
  }

  /** A mixed run gives concepts the odd question; a pool that is too small for the count is used whole. */
  fun draw(count: Int, kind: QuizKind, random: Random): List<Drill> {
    val numbers = Numbers.generators.shuffled(random)
    val concepts = Concepts.all.shuffled(random)
    return when (kind) {
      QuizKind.NUMBERS -> numbers.take(count.coerceIn(1, numbers.size)).map { it(random) }
      QuizKind.CONCEPTS -> concepts.take(count.coerceIn(1, concepts.size))
      QuizKind.ALL -> {
        val wanted = count.coerceIn(1, numbers.size + concepts.size)
        val numbersTaken = minOf(wanted - minOf((wanted + 1) / 2, concepts.size), numbers.size)
        val conceptsTaken = minOf(wanted - numbersTaken, concepts.size)
        (concepts.take(conceptsTaken) + numbers.take(numbersTaken).map { it(random) }).shuffled(random)
      }
    }
  }

  private fun askNumeric(question: NumericQuestion, terminal: Terminal): Boolean? {
    val given = askNumber(terminal, question.unit) ?: return null
    val correct = isCorrect(given, question.answer, question.tolerance)
    terminal.out(verdict(correct) + "Answer: ${shown(question.answer)} ${question.unit}")
    return correct
  }

  private fun askChoice(question: ChoiceQuestion, terminal: Terminal, random: Random): Boolean? {
    val options = question.options.shuffled(random)
    options.forEachIndexed { index, option -> terminal.out("  ${index + 1}. $option") }
    val picked = askOption(terminal, options.size) ?: return null
    val correct = options[picked - 1] == question.answer
    terminal.out(verdict(correct) + "Answer: ${options.indexOf(question.answer) + 1}. ${question.answer}")
    return correct
  }

  private fun verdict(correct: Boolean): String = if (correct) "  CORRECT. " else "  NOT QUITE. "

  private fun askNumber(terminal: Terminal, unit: String): Double? {
    while (true) {
      val raw = terminal.ask("  Answer ($unit): ") ?: return null
      parseNumber(raw)?.let { return it }
      terminal.out("  Enter a number, for example 12.5")
    }
  }

  private fun askOption(terminal: Terminal, size: Int): Int? {
    while (true) {
      val raw = terminal.ask("  Pick a number: ") ?: return null
      raw.trim().toIntOrNull()?.takeIf { it in 1..size }?.let { return it }
      terminal.out("  Enter a number from 1 to $size.")
    }
  }

  private fun inputClosed(terminal: Terminal, score: Int): Int {
    terminal.out()
    terminal.out("(Run `make quiz` in a terminal to answer.)")
    return score
  }

  /** Accepts a comma or a dot as the decimal separator, and thousands written as 1.200.000. */
  fun parseNumber(text: String): Double? {
    var t = text.trim().replace("%", "").replace(" ", "").replace(",", ".")
    if (t.count { it == '.' } > 1) {
      t = t.replace(".", "")
    }
    return t.toDoubleOrNull()
  }

  fun isCorrect(given: Double, answer: Double, tolerance: Tolerance): Boolean =
    when (tolerance) {
      is Tolerance.Absolute -> abs(given - answer) <= tolerance.amount + EPSILON
      is Tolerance.Relative ->
        if (tolerance.share == 0.0) abs(given - answer) < EPSILON else abs(given - answer) <= tolerance.share * maxOf(abs(answer), EPSILON)
    }

  /** Four significant digits and no thousands separator, because the answer prompt reads a comma as the decimal point. */
  fun shown(value: Double): String = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros().toPlainString()

  private const val EPSILON = 1e-9
  private const val SIGNIFICANT_DIGITS = 4
  const val GUIDE = "docs/investigation-guide.md"
}

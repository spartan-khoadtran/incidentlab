package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.Terminal
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.random.Random

/** Drills for the guide: numbers (fresh values every run) and concepts (shuffled multiple choice). */
object Quiz {

  fun run(count: Int, kind: QuizKind, terminal: Terminal, random: Random = Random.Default): Int {
    val drills = draw(count, kind, random)
    var score = 0
    var skipped = 0
    terminal.out("Press Enter with no answer to skip a question.")
    drills.forEachIndexed { index, drill ->
      terminal.out()
      terminal.out("Question ${index + 1}/${drills.size}. ${drill.text}")
      val mark = when (drill) {
        is NumericQuestion -> askNumeric(drill, terminal)
        is ChoiceQuestion -> askChoice(drill, terminal, random)
      } ?: return inputClosed(terminal, score)
      when (mark) {
        Mark.RIGHT -> score += 1
        Mark.SKIPPED -> skipped += 1
        Mark.WRONG -> Unit
      }
      terminal.out("  ${drill.explanation}")
      terminal.out("  Read again in ${GuideIndex.where(drill.doc)}")
    }
    terminal.out()
    terminal.out("Result: $score/${drills.size}" + if (skipped > 0) " ($skipped skipped)" else "")
    return score
  }

  private enum class Mark(val label: String) {
    RIGHT("  CORRECT. "),
    WRONG("  NOT QUITE. "),
    SKIPPED("  SKIPPED. ")
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

  private fun askNumeric(question: NumericQuestion, terminal: Terminal): Mark? {
    val given = askNumber(terminal, question.unit) ?: return null
    val mark = when (given) {
      Typed.Skip -> Mark.SKIPPED
      is Typed.Value -> if (isCorrect(given.value, question.answer, question.tolerance)) Mark.RIGHT else Mark.WRONG
    }
    terminal.out(mark.label + "Answer: ${shown(question.answer)} ${question.unit}")
    return mark
  }

  private fun askChoice(question: ChoiceQuestion, terminal: Terminal, random: Random): Mark? {
    val options = question.options.shuffled(random)
    options.forEachIndexed { index, option -> terminal.out("  ${index + 1}. $option") }
    val picked = askOption(terminal, options.size) ?: return null
    val mark = when (picked) {
      Typed.Skip -> Mark.SKIPPED
      is Typed.Value -> if (options[picked.value - 1] == question.answer) Mark.RIGHT else Mark.WRONG
    }
    terminal.out(mark.label + "Answer: ${options.indexOf(question.answer) + 1}. ${question.answer}")
    return mark
  }

  private sealed interface Typed<out T> {
    data object Skip : Typed<Nothing>

    data class Value<T>(val value: T) : Typed<T>
  }

  private fun askNumber(terminal: Terminal, unit: String): Typed<Double>? {
    while (true) {
      val raw = terminal.ask("  Answer ($unit): ") ?: return null
      if (raw.isBlank()) return Typed.Skip
      parseNumber(raw)?.let { return Typed.Value(it) }
      terminal.out("  Enter a number, for example 12.5")
    }
  }

  private fun askOption(terminal: Terminal, size: Int): Typed<Int>? {
    while (true) {
      val raw = terminal.ask("  Pick a number: ") ?: return null
      if (raw.isBlank()) return Typed.Skip
      raw.trim().toIntOrNull()?.takeIf { it in 1..size }?.let { return Typed.Value(it) }
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
}

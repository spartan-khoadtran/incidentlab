package com.c0x12c.incidentlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.Terminal
import kotlin.random.Random
import org.junit.jupiter.api.Test

class QuizTest {

  @Test
  fun `numbers are read the way people type them`() {
    assertThat(Quiz.parseNumber("12,5")).isEqualTo(12.5)
    assertThat(Quiz.parseNumber(" 1.200.000 ")).isEqualTo(1_200_000.0)
    assertThat(Quiz.parseNumber("85%")).isEqualTo(85.0)
    assertThat(Quiz.parseNumber("abc")).isNull()
  }

  @Test
  fun `a shown answer has no thousands separator, so typing it back is correct`() {
    assertThat(Quiz.shown(3000.0)).isEqualTo("3000")
    assertThat(Quiz.shown(37.5)).isEqualTo("37.5")
    assertThat(Quiz.shown(0.0375)).isEqualTo("0.0375")
    assertThat(Quiz.parseNumber(Quiz.shown(3000.0))).isEqualTo(3000.0)
  }

  @Test
  fun `tolerances are relative or absolute`() {
    assertThat(Quiz.isCorrect(103.0, 100.0, Tolerance.Relative(0.03))).isTrue()
    assertThat(Quiz.isCorrect(104.0, 100.0, Tolerance.Relative(0.03))).isFalse()
    assertThat(Quiz.isCorrect(99.52, 99.5, Tolerance.Absolute(0.02))).isTrue()
    assertThat(Quiz.isCorrect(1200.0, 1200.0, Tolerance.Relative(0.0))).isTrue()
  }

  @Test
  fun `every generator accepts its own answer, and the answer typed back as shown`() {
    val random = Random(7)
    for (generate in Numbers.generators) {
      repeat(40) {
        val question = generate(random)
        assertThat(question.answer.isFinite()).isTrue()
        assertThat(Quiz.isCorrect(question.answer, question.answer, question.tolerance)).isTrue()
        assertThat(Quiz.isCorrect(Quiz.parseNumber(Quiz.shown(question.answer))!!, question.answer, question.tolerance)).isTrue()
      }
    }
  }

  @Test
  fun `a share or a utilization never goes above 100 percent`() {
    val random = Random(5)
    for (generate in Numbers.generators) {
      repeat(60) {
        val question = generate(random)
        if (question.unit == "%") {
          assertThat(question.answer).isBetween(0.0, 100.0)
        }
      }
    }
  }

  @Test
  fun `explanations never show floating-point noise`() {
    val random = Random(11)
    val noise = Regex("\\d\\.\\d{7,}")
    for (generate in Numbers.generators) {
      repeat(30) {
        val question = generate(random)
        assertThat(noise.containsMatchIn(question.text)).isFalse()
        assertThat(noise.containsMatchIn(question.explanation)).isFalse()
      }
    }
  }

  @Test
  fun `every concept question has four distinct options and one answer`() {
    val texts = Concepts.all.map { it.text }
    assertThat(texts.toSet()).hasSize(texts.size)
    for (question in Concepts.all) {
      assertThat(question.others).hasSize(3)
      assertThat(question.options.toSet()).hasSize(4)
      assertThat(question.text.isNotBlank() && question.explanation.isNotBlank() && question.doc.isNotBlank()).isTrue()
    }
  }

  @Test
  fun `a mixed draw takes about half numbers and half concepts, and a kind draws only its own`() {
    val mixed = Quiz.draw(5, QuizKind.ALL, Random(2))
    assertThat(mixed).hasSize(5)
    assertThat(mixed.count { it is ChoiceQuestion }).isEqualTo(3)
    assertThat(mixed.count { it is NumericQuestion }).isEqualTo(2)
    assertThat(Quiz.draw(4, QuizKind.NUMBERS, Random(2)).filterIsInstance<ChoiceQuestion>()).isEmpty()
    assertThat(Quiz.draw(4, QuizKind.CONCEPTS, Random(2)).filterIsInstance<NumericQuestion>()).isEmpty()
    assertThat(Quiz.draw(500, QuizKind.ALL, Random(2))).hasSize(Numbers.generators.size + Concepts.all.size)
  }

  @Test
  fun `a scripted run scores the right answers for numbers and for shuffled options`() {
    val expected = Quiz.draw(6, QuizKind.ALL, Random(3))
    val terminal = object : Terminal {
      val lines = mutableListOf<String>()
      private var asked = 0

      override fun out(line: String) {
        lines.add(line)
      }

      override fun ask(prompt: String): String =
        when (val drill = expected[asked++]) {
          is NumericQuestion -> drill.answer.toString()
          is ChoiceQuestion -> lines.last { it.endsWith(". ${drill.answer}") }.trim().substringBefore('.')
        }
    }

    val score = Quiz.run(6, QuizKind.ALL, terminal, Random(3))

    assertThat(score).isEqualTo(6)
    assertThat(terminal.lines.last()).isEqualTo("Result: 6/6")
    assertThat(terminal.lines.count { it.startsWith("  CORRECT.") }).isEqualTo(6)
  }

  @Test
  fun `a wrong option is reported with the right one`() {
    val drill = Quiz.draw(1, QuizKind.CONCEPTS, Random(1)).single() as ChoiceQuestion
    val terminal = object : Terminal {
      val lines = mutableListOf<String>()

      override fun out(line: String) {
        lines.add(line)
      }

      override fun ask(prompt: String): String = lines.last { it.matches(OPTION) && !it.endsWith(". ${drill.answer}") }.trim().substringBefore('.')
    }

    val score = Quiz.run(1, QuizKind.CONCEPTS, terminal, Random(1))

    assertThat(score).isEqualTo(0)
    assertThat(terminal.lines.any { it.startsWith("  NOT QUITE. Answer: ") && it.endsWith(drill.answer) }).isTrue()
  }

  private companion object {
    val OPTION = Regex("  \\d\\. .*")
  }
}

package com.c0x12c.redlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.redlab.labctl.Terminal
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
  fun `every generator accepts its own answer`() {
    val random = Random(7)
    for (generate in Quiz.generators) {
      repeat(20) {
        val question = generate(random)
        assertThat(question.answer.isFinite()).isTrue()
        assertThat(Quiz.isCorrect(question.answer, question.answer, question.tolerance)).isTrue()
      }
    }
  }

  @Test
  fun `explanations never show floating-point noise`() {
    val random = Random(11)
    val noise = Regex("\\d\\.\\d{7,}")
    for (generate in Quiz.generators) {
      repeat(30) {
        val question = generate(random)
        assertThat(noise.containsMatchIn(question.text)).isFalse()
        assertThat(noise.containsMatchIn(question.explanation)).isFalse()
      }
    }
  }

  @Test
  fun `a scripted run scores the right answers`() {
    val answers = ArrayDeque<String>()
    val terminal = object : Terminal {
      val lines = mutableListOf<String>()

      override fun out(line: String) {
        lines.add(line)
      }

      override fun ask(prompt: String): String = answers.removeFirst()
    }
    val preview = Random(3)
    val expected = Quiz.generators.shuffled(preview).take(2).map { it(preview) }
    expected.forEach { answers.addLast(it.answer.toString()) }

    val score = Quiz.run(2, terminal, Random(3))

    assertThat(score).isEqualTo(2)
    assertThat(terminal.lines.last()).isEqualTo("Result: 2/2")
  }
}

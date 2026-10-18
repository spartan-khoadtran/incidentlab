package com.c0x12c.incidentlab.labctl.quiz

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import kotlin.random.Random
import org.junit.jupiter.api.Test

class QuizSessionsTest {

  private var clock = 0L
  private var next = 0

  private fun sessions(capacity: Int = 50) = QuizSessions(Random(3), { clock }, capacity = capacity, ids = { "s${next++}" })

  /** The same draw the sessions make, so the test knows the answers the API never sends. */
  private fun drills(count: Int, kind: QuizKind): List<Drill> = Quiz.draw(count, kind, Random(3))

  private fun grade(outcome: Outcome<QuizGrade>): QuizGrade = (outcome as Outcome.Done).value

  @Test
  fun `a question carries no answer and no explanation`() {
    val start = sessions().open(QuizSessions.MAX_COUNT, QuizKind.ALL)
    val drills = drills(QuizSessions.MAX_COUNT, QuizKind.ALL)

    assertThat(start.questions.map { it.text }).isEqualTo(drills.map { it.text })
    start.questions.zip(drills).forEach { (question, drill) ->
      val json = LabJson.mapper.writeValueAsString(question)
      val keys = LabJson.mapper.readTree(json).fieldNames().asSequence().toSet()
      when (drill) {
        is NumericQuestion -> assertThat(keys).containsOnly("index", "kind", "text", "unit")
        is ChoiceQuestion -> {
          assertThat(keys).containsOnly("index", "kind", "text", "options")
          assertThat(question.options!!.toSet()).isEqualTo(drill.options.toSet())
        }
      }
      assertThat(json.contains(drill.explanation)).isFalse()
    }
  }

  @Test
  fun `the options are shuffled, so the first one is not always the answer`() {
    val start = sessions().open(QuizSessions.MAX_COUNT, QuizKind.CONCEPTS)
    val drills = drills(QuizSessions.MAX_COUNT, QuizKind.CONCEPTS).map { it as ChoiceQuestion }

    val firstIsAnswer = start.questions.zip(drills).count { (question, drill) -> question.options!!.first() == drill.answer }
    assertThat(firstIsAnswer < drills.size / 2).isTrue()
  }

  @Test
  fun `a number is graded with the CLI tolerance, and a typed comma counts as the decimal point`() {
    val sessions = sessions()
    val start = sessions.open(QuizSessions.MAX_COUNT, QuizKind.NUMBERS)
    val drills = drills(QuizSessions.MAX_COUNT, QuizKind.NUMBERS).map { it as NumericQuestion }
    val relative = drills.indexOfFirst { (it.tolerance as? Tolerance.Relative)?.share?.let { share -> share >= 0.01 } == true }
    val drill = drills[relative]
    val share = (drill.tolerance as Tolerance.Relative).share
    val inside = drill.answer * (1 + share * 0.9)

    val right = grade(sessions.grade(start.session, relative, QuizAnswer.Number(inside)))
    assertThat(right.correct).isTrue()
    assertThat(right.answer).isEqualTo("${Quiz.shown(drill.answer)} ${drill.unit}")
    assertThat(right.explanation).isEqualTo(drill.explanation)

    val other = drills.indexOfLast { it.tolerance is Tolerance.Relative && (it.tolerance as Tolerance.Relative).share >= 0.01 }
    val wrong = drills[other]
    val far = wrong.answer * (1 + (wrong.tolerance as Tolerance.Relative).share * 1.5) + 1
    assertThat(grade(sessions.grade(start.session, other, QuizAnswer.Text(far.toString().replace('.', ',')))).correct).isFalse()
  }

  @Test
  fun `a choice is graded by its text, and the first answer to a question stands`() {
    val sessions = sessions()
    val start = sessions.open(5, QuizKind.CONCEPTS)
    val drill = drills(5, QuizKind.CONCEPTS).first() as ChoiceQuestion

    val wrong = grade(sessions.grade(start.session, 0, QuizAnswer.Text(drill.others.first())))
    assertThat(wrong.correct).isFalse()
    assertThat(wrong.answer).isEqualTo(drill.answer)
    assertThat(grade(sessions.grade(start.session, 0, QuizAnswer.Text(drill.answer)))).isEqualTo(wrong)
  }

  @Test
  fun `a bad answer, question or session is refused`() {
    val sessions = sessions()
    val start = sessions.open(5, QuizKind.CONCEPTS)

    assertThat((sessions.grade(start.session, 0, QuizAnswer.Text("not an option")) as Outcome.Refused).refusal).isInstanceOf(Refusal.BadAnswer::class)
    assertThat((sessions.grade(start.session, 0, QuizAnswer.Number(1.0)) as Outcome.Refused).refusal).isInstanceOf(Refusal.BadAnswer::class)
    assertThat((sessions.grade(start.session, 5, QuizAnswer.Number(1.0)) as Outcome.Refused).refusal).isEqualTo(Refusal.UnknownQuestion(5))
    assertThat((sessions.grade("nope", 0, QuizAnswer.Number(1.0)) as Outcome.Refused).refusal).isEqualTo(Refusal.UnknownSession)
  }

  @Test
  fun `the oldest session is dropped past the capacity, and an idle one after two hours`() {
    val sessions = sessions(capacity = 2)
    val first = sessions.open(1, QuizKind.CONCEPTS).session
    val second = sessions.open(1, QuizKind.CONCEPTS).session
    val third = sessions.open(1, QuizKind.CONCEPTS).session

    assertThat(sessions.grade(first, 0, QuizAnswer.Text("x"))).isEqualTo(Outcome.Refused(Refusal.UnknownSession))
    assertThat((sessions.grade(second, 0, QuizAnswer.Text("x")) as Outcome.Refused).refusal).isInstanceOf(Refusal.BadAnswer::class)
    clock += TWO_HOURS_MS - 1
    assertThat((sessions.grade(third, 0, QuizAnswer.Text("x")) as Outcome.Refused).refusal).isInstanceOf(Refusal.BadAnswer::class)
    clock += TWO_HOURS_MS + 1
    assertThat(sessions.grade(third, 0, QuizAnswer.Text("x"))).isEqualTo(Outcome.Refused(Refusal.UnknownSession))
  }

  private companion object {
    const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
  }
}

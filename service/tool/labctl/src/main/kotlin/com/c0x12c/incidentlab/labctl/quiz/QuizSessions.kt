package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Done
import com.c0x12c.incidentlab.labctl.exercise.Outcome.Refused
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.guide.GuidePointer
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/** A question as the learner sees it before answering: no answer, no explanation. Choice options are already shuffled. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuizQuestionView(
  val index: Int,
  /** number or choice. */
  val kind: String,
  val text: String,
  val unit: String? = null,
  val options: List<String>? = null
)

data class QuizStart(
  val session: String,
  val questions: List<QuizQuestionView>
)

data class QuizGrade(
  val correct: Boolean,
  /** The right answer as the CLI prints it: a number with its unit, or the option text. */
  val answer: String,
  val explanation: String,
  val guide: List<GuidePointer>
)

sealed interface QuizAnswer {
  data class Number(val value: Double) : QuizAnswer

  /** Typed text: an option, or a number written the way people type it. */
  data class Text(val value: String) : QuizAnswer
}

/**
 * `make quiz` for the browser: a session draws its questions once, and each question is graded on
 * its own, with the same tolerance as the CLI. Sessions live in memory, so a daemon restart drops them.
 */
class QuizSessions(
  private val random: Random = Random.Default,
  private val clock: () -> Long = System::currentTimeMillis,
  private val idleLimit: Duration = Duration.ofHours(2),
  private val capacity: Int = 50,
  private val ids: () -> String = { UUID.randomUUID().toString() }
) {

  private class Session(
    var lastUsed: Long,
    val drills: List<Drill>,
    val options: List<List<String>?>,
    val graded: MutableMap<Int, QuizGrade> = HashMap()
  )

  private val sessions = LinkedHashMap<String, Session>()

  @Synchronized
  fun open(count: Int, kind: QuizKind): QuizStart {
    dropIdle()
    val drills = Quiz.draw(count, kind, random)
    val options = drills.map { (it as? ChoiceQuestion)?.options?.shuffled(random) }
    val id = ids()
    sessions[id] = Session(clock(), drills, options)
    while (sessions.size > capacity) {
      sessions.remove(sessions.keys.first())
    }
    return QuizStart(id, drills.mapIndexed { index, drill -> view(index, drill, options[index]) })
  }

  /** The first answer to a question decides; posting again returns the same grade. */
  @Synchronized
  fun grade(id: String, index: Int, given: QuizAnswer): Outcome<QuizGrade> {
    dropIdle()
    val session = sessions.remove(id) ?: return Refused(Refusal.UnknownSession)
    session.lastUsed = clock()
    sessions[id] = session
    val drill = session.drills.getOrNull(index) ?: return Refused(Refusal.UnknownQuestion(index))
    session.graded[index]?.let { return Done(it) }
    val correct = when (drill) {
      is NumericQuestion -> {
        val value = when (given) {
          is QuizAnswer.Number -> given.value
          is QuizAnswer.Text -> Quiz.parseNumber(given.value)
        }?.takeIf { it.isFinite() } ?: return Refused(Refusal.BadAnswer("Question ${index + 1} takes a number, for example 12.5."))
        Quiz.isCorrect(value, drill.answer, drill.tolerance)
      }
      is ChoiceQuestion -> {
        val text = (given as? QuizAnswer.Text)?.value?.takeIf { it in drill.options }
          ?: return Refused(Refusal.BadAnswer("Question ${index + 1} takes the text of one of its options."))
        text == drill.answer
      }
    }
    val answer = when (drill) {
      is NumericQuestion -> "${Quiz.shown(drill.answer)} ${drill.unit}"
      is ChoiceQuestion -> drill.answer
    }
    return Done(QuizGrade(correct, answer, drill.explanation, GuideIndex.pointers(drill.doc)).also { session.graded[index] = it })
  }

  private fun dropIdle() {
    val oldest = clock() - idleLimit.toMillis()
    sessions.values.removeIf { it.lastUsed < oldest }
  }

  private fun view(index: Int, drill: Drill, options: List<String>?): QuizQuestionView =
    when (drill) {
      is NumericQuestion -> QuizQuestionView(index, NUMBER, drill.text, unit = drill.unit)
      is ChoiceQuestion -> QuizQuestionView(index, CHOICE, drill.text, options = options)
    }

  companion object {
    const val NUMBER = "number"
    const val CHOICE = "choice"
    const val DEFAULT_COUNT = 20
    const val MAX_COUNT = 300
  }
}

package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.NewOptions
import com.c0x12c.incidentlab.labctl.quiz.QuizKind
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog

class UsageError(message: String) : RuntimeException(message)

data class QuizOptions(
  val count: Int,
  val kind: QuizKind = QuizKind.ALL
)

/** Turns the arguments the Makefile passes into typed options, with a message a learner can act on. */
object CommandLine {

  fun newOptions(args: List<String>): NewOptions {
    var options = NewOptions()
    var index = 0
    while (index < args.size) {
      when (val flag = args[index]) {
        "--level" -> options = options.copy(level = level(args.getOrNull(++index)))
        "--topic" -> options = options.copy(topic = topic(args.getOrNull(++index)))
        "--id" -> options = options.copy(id = args.getOrNull(++index).orEmpty())
        "--force" -> options = options.copy(force = true)
        "--mode" -> options = options.copy(mode = mode(args.getOrNull(++index)))
        else -> throw UsageError("Unknown option '$flag'.")
      }
      index++
    }
    return options
  }

  fun loadMultiplier(raw: String?): Double {
    val value = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()
      ?: throw UsageError("X must be a number, for example `make load X=1.5`. X=1 is the normal load.")
    if (value < Exercises.MIN_LOAD || value > Exercises.MAX_LOAD) {
      throw UsageError("X must be between ${Exercises.MIN_LOAD} and ${Exercises.MAX_LOAD.toInt()}. X=1 is the normal load.")
    }
    return value
  }

  fun quizOptions(args: List<String>, defaultCount: Int): QuizOptions {
    var options = QuizOptions(count = defaultCount)
    var index = 0
    while (index < args.size) {
      when (val flag = args[index]) {
        "-n" -> options = options.copy(count = quizCount(args.getOrNull(++index)))
        "--kind" -> options = options.copy(kind = quizKind(args.getOrNull(++index)))
        else -> throw UsageError("Unknown option '$flag'.")
      }
      index++
    }
    return options
  }

  private fun quizCount(raw: String?): Int =
    raw?.toIntOrNull()?.takeIf { it > 0 }
      ?: throw UsageError("N must be a whole number above 0, for example `make quiz N=5`.")

  private fun quizKind(raw: String?): QuizKind =
    QuizKind.entries.firstOrNull { it.flag == raw }
      ?: throw UsageError("KIND must be one of ${QuizKind.entries.joinToString(", ") { it.flag }}, for example `make quiz KIND=concepts`.")

  private fun mode(raw: String?): String =
    raw?.takeIf { it in Exercises.MODES }
      ?: throw UsageError("MODE must be one of ${Exercises.MODES.joinToString(", ")}.")

  private fun level(raw: String?): Int {
    val levels = ScenarioCatalog.all.map { it.level }.distinct().sorted()
    return raw?.toIntOrNull()?.takeIf { it in levels }
      ?: throw UsageError("LEVEL must be one of ${levels.joinToString(", ")}. See `make list`.")
  }

  private fun topic(raw: String?): String =
    raw?.takeIf { it in Answers.TOPICS }
      ?: throw UsageError("Unknown TOPIC '${raw.orEmpty()}'. Topics: ${Answers.TOPICS.joinToString(", ")}.")
}

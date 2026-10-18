package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.bench.BenchCatalog
import com.c0x12c.incidentlab.labctl.bench.BenchOptions
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.NewOptions
import com.c0x12c.incidentlab.labctl.quiz.QuizKind
import com.c0x12c.incidentlab.labctl.ramp.RampOptions
import com.c0x12c.incidentlab.labctl.ramp.RampPlan
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

  fun rampOptions(args: List<String>): RampOptions {
    var options = RampOptions()
    var index = 0
    while (index < args.size) {
      when (val flag = args[index]) {
        "--every" -> options = options.copy(every = stepSeconds(args.getOrNull(++index)))
        else -> throw UsageError("Unknown option '$flag'.")
      }
      index++
    }
    return options
  }

  fun benchOptions(args: List<String>): BenchOptions {
    var options = BenchOptions()
    var index = 0
    while (index < args.size) {
      when (val flag = args[index]) {
        "--clients" -> options = options.copy(clients = bounded(args.getOrNull(++index), "CLIENTS", BenchCatalog.MIN_CLIENTS, BenchCatalog.MAX_CLIENTS, "make bench CLIENTS=50"))
        "--seconds" -> options = options.copy(seconds = bounded(args.getOrNull(++index), "SECONDS", BenchCatalog.MIN_SECONDS, BenchCatalog.MAX_SECONDS, "make bench SECONDS=180"))
        else -> throw UsageError("Unknown option '$flag'.")
      }
      index++
    }
    return options
  }

  private fun bounded(raw: String?, name: String, min: Int, max: Int, example: String): Int =
    raw?.toIntOrNull()?.takeIf { it in min..max }
      ?: throw UsageError("$name must be a whole number between $min and $max, for example `$example`.")

  private fun stepSeconds(raw: String?): Int =
    raw?.toIntOrNull()?.takeIf { it in RampPlan.MIN_STEP_SECONDS..RampPlan.MAX_STEP_SECONDS }
      ?: throw UsageError("STEP must be a whole number of seconds between ${RampPlan.MIN_STEP_SECONDS} and ${RampPlan.MAX_STEP_SECONDS}, for example `make ramp STEP=45`.")

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

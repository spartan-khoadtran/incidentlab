package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

/**
 * Numeric drills for docs/methods.md. Each generator draws from a short list of values and hands them
 * to a formula function, so a test can check the formula on fixed values.
 */
internal object MethodsNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    { r -> methodRSplit(TASK_MS.random(r), STEP_MS.random(r), FACTOR.random(r)) },
    { r -> methodRShare(TASK_MS.random(r), STEP_MS.random(r)) },
    { r -> baselineDrift(START_MS.random(r), GROWTH_PERCENT.random(r), WEEKS.random(r)) }
  )

  fun methodRSplit(taskMs: Int, stepMs: Int, factor: Int): NumericQuestion {
    val fasterStepMs = stepMs / factor.toDouble()
    val answer = taskMs - stepMs + fasterStepMs
    return NumericQuestion(
      "One checkout takes $taskMs ms. One step inside it takes $stepMs ms. You make that step $factor times faster and nothing else changes. How long does the checkout take now, in ms?",
      answer, Tolerance.Relative(0.01), "ms",
      "The step goes from $stepMs to $stepMs / $factor = ${shown(fasterStepMs)} ms. $taskMs − $stepMs + ${shown(fasterStepMs)} = ${shown(answer)} ms. The most a step can give back is its own share: $stepMs of $taskMs ms is ${shown(stepMs / taskMs.toDouble() * PERCENT)}%. Method R works on the largest share first.",
      "Methods for the root cause > Method R"
    )
  }

  fun methodRShare(taskMs: Int, stepMs: Int): NumericQuestion {
    val answer = stepMs / taskMs.toDouble() * PERCENT
    return NumericQuestion(
      "One checkout takes $taskMs ms, and its pool wait is $stepMs ms of that. By Method R, what is the most a fix to the pool wait can cut from the checkout, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "$stepMs / $taskMs = ${shown(answer)}%. A step cut by half gives back half of that, never more. That is why Method R starts from the largest share and stops when the task meets its target.",
      "Methods for the root cause > Method R"
    )
  }

  fun baselineDrift(startMs: Int, growthPercent: Int, weeks: Int): NumericQuestion {
    val factor = (1 + growthPercent / PERCENT).pow(weeks)
    val answer = startMs * factor
    return NumericQuestion(
      "A saved baseline shows p99 of /checkout at $startMs ms. It grows $growthPercent% every week, and no single week fires an alert. What is p99 after $weeks weeks, in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "$startMs × ${shown(1 + growthPercent / PERCENT)}^$weeks = $startMs × ${String.format(Locale.ROOT, "%.2f", factor)} = ${shown(answer)} ms. Only a saved baseline shows the drift. Compare with offset 1w, and with the week the baseline was taken.",
      "Methods for the root cause > Baseline statistics"
    )
  }

  private val TASK_MS = listOf(400, 600, 1_000)
  private val STEP_MS = listOf(50, 80, 100, 250, 300)
  private val FACTOR = listOf(2, 4, 5, 10)
  private val START_MS = listOf(200, 300, 500)
  private val GROWTH_PERCENT = listOf(2, 5)
  private val WEEKS = listOf(4, 8, 12, 26)

  private const val PERCENT = 100.0
}

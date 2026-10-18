package com.c0x12c.incidentlab.labctl.ramp

import kotlin.math.abs

/** One step of the ramp as Prometheus measured it. A null is a window Prometheus had no sample for. */
data class StepReading(
  val index: Int,
  val targetRps: Int,
  val offered: Double?,
  val achieved: Double?,
  val p99Seconds: Double?,
  val errorShare: Double?
)

/** What the learner typed: two rates in rps (0 for no knee) and a resource key from [RampCatalog.RESOURCES]. */
data class RampAnswer(
  val kneeRps: Double,
  val maxRps: Double,
  val resource: String
)

data class RampVerdict(
  val knee: Int?,
  val maxThroughput: Double?,
  val kneeCorrect: Boolean,
  val maxCorrect: Boolean,
  val resourceCorrect: Boolean
) {
  val points: Double =
    (if (kneeCorrect) RampMath.KNEE_POINTS else 0.0) + (if (maxCorrect) RampMath.MAX_POINTS else 0.0) + (if (resourceCorrect) RampMath.RESOURCE_POINTS else 0.0)

  val missed: List<String> =
    listOfNotNull("knee".takeIf { !kneeCorrect }, "max throughput".takeIf { !maxCorrect }, "resource".takeIf { !resourceCorrect })
}

/** The knee and the maximum from a step table, and the score of an answer against them. */
object RampMath {

  const val KNEE_P99_FACTOR = 3.0
  const val KNEE_ERROR_SHARE = 0.01
  const val MAX_TOLERANCE = 0.10
  const val KNEE_POINTS = 2.0
  const val MAX_POINTS = 2.0
  const val RESOURCE_POINTS = 1.0
  const val TOTAL_POINTS = KNEE_POINTS + MAX_POINTS + RESOURCE_POINTS

  /** The first step whose p99 is above 3 times the first step's p99, or whose error share is above 1%. */
  fun knee(steps: List<StepReading>): Int? {
    val base = steps.firstOrNull()?.p99Seconds
    return steps.firstOrNull { step ->
      val slow = base != null && step.p99Seconds != null && step.p99Seconds > base * KNEE_P99_FACTOR
      slow || (step.errorShare ?: 0.0) > KNEE_ERROR_SHARE
    }?.index
  }

  fun maxThroughput(steps: List<StepReading>): Double? = steps.mapNotNull { it.achieved }.maxOrNull()

  /** The step whose target rate is nearest to a rate the learner typed. */
  fun nearestStep(rps: Double, steps: List<StepReading>): Int? = steps.minByOrNull { abs(it.targetRps - rps) }?.index

  /**
   * The knee counts within one step of the measured one, the maximum within 10%, and the resource
   * when it is the hidden one. With no measured knee, 0 is the right knee and "none" a right resource.
   */
  fun verdict(answer: RampAnswer, steps: List<StepReading>, hiddenResource: String): RampVerdict {
    val knee = knee(steps)
    val typed = answer.kneeRps.takeIf { it > 0 }?.let { nearestStep(it, steps) }
    val kneeCorrect = if (knee == null) answer.kneeRps <= 0 else typed != null && abs(typed - knee) <= 1
    val max = maxThroughput(steps)
    val maxCorrect = max != null && abs(answer.maxRps - max) <= MAX_TOLERANCE * max
    val accepted = setOf(hiddenResource) + (if (knee == null) setOf(RampCatalog.NONE) else emptySet())
    return RampVerdict(knee, max, kneeCorrect, maxCorrect, answer.resource in accepted)
  }
}

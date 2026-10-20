package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.guide.GuidePointer
import com.c0x12c.incidentlab.labctl.state.Choices
import com.c0x12c.incidentlab.labctl.state.HistoryEntry
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonUnwrapped

/**
 * The only view of an open exercise. It has no field for the scenario's id, title, answer, explanation,
 * steps, evidence, cli, guide pointer or rule-outs. Its [options] hold the right keys only among others
 * drawn from the ticket's symptom.
 */
data class ActiveView(
  val n: Int,
  /** Names this exercise. A write that sends it is refused when another exercise is open by then. */
  val key: String,
  val mode: String,
  /** Null for the ramp and the bench, which have no level. */
  val level: Int?,
  /** The text the CLI prints when the exercise starts, with the ticket time in it. */
  val ticket: String,
  val startedAt: Double,
  val elapsedSeconds: Double,
  val hintsUsed: Int,
  val hintsTotal: Int,
  val hintPenalty: Double,
  /** The hints already taken, in order. */
  val hints: List<String>,
  val answered: Boolean,
  /** The toolbox rules in toolbox mode. */
  val rules: String?,
  /** What the four questions offer, for an incident or a toolbox exercise. Null for the ramp and the bench. */
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val options: AnswerOptions? = null
)

/** One choice of a question: a stable key and the text a learner reads. */
data class Option(
  val key: String,
  val label: String,
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val group: String? = null
)

data class AnswerView(
  val nature: List<Option>,
  val location: List<Option>,
  val signal: List<Option>,
  val cause: Option,
  val partial: List<Option>
)

/**
 * The options one open incident offers, drawn from its ticket's [com.c0x12c.incidentlab.labctl.scenario.Symptom]
 * only. Every scenario with the same symptom offers the same lists, so they tell the learner nothing the ticket did not.
 */
data class AnswerOptions(
  val natures: List<Option>,
  val locations: List<Option>,
  val signals: List<Option>,
  val causes: List<Option>
)

/** One cause the exercise offered, with the check that rules it in or out. Only in a [Reveal]. */
data class RuleOut(
  val cause: Option,
  val check: String,
  val correct: Boolean,
  /** A partial cause: one step before the root cause, worth 1 point. */
  val partial: Boolean = false
)

/** Everything about the scenario. Only built once the exercise is answered or revealed. */
data class Reveal(
  val id: String,
  val title: String,
  val level: Int,
  val topics: List<String>,
  val answer: AnswerView,
  val explanation: String,
  val steps: List<String>,
  /** PromQL. */
  val evidence: List<String>,
  val cli: List<String>,
  val guide: List<GuidePointer>,
  /** Every cause the exercise offered, in the order offered, with its check. */
  val ruleOuts: List<RuleOut> = emptyList()
)

data class Verdicts(
  val nature: String,
  val location: String,
  val signal: String,
  /** correct, partial or wrong. */
  val cause: String
)

/** A scored answer: [Result] for an incident, a ramp result for a ramp. */
interface ScoredResult

data class Result(
  val n: Int,
  val score: Double,
  val raw: Double,
  val max: Double,
  val hintsUsed: Int,
  val verdicts: Verdicts,
  val missed: List<String>,
  val reveal: Reveal,
  /** The four keys the learner picked, so a reloaded page can show them again. */
  val choices: Choices? = null
) : ScoredResult

data class Revealed(
  val n: Int,
  /** True when the exercise had been answered or revealed before, so this reveal added no history entry. */
  val wasAnswered: Boolean,
  val reveal: Reveal
)

/** The steps of a ramp, as the API shows them next to its view. */
data class PlanView(
  val steps: List<Int>,
  val every: Int,
  val totalSeconds: Int
)

/** The open exercise with what its mode adds: the reveal and the result once answered, the ramp's plan, the bench's end. */
data class ExerciseView(
  @get:JsonUnwrapped
  val active: ActiveView,
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val reveal: Reveal? = null,
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val result: ScoredResult? = null,
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val plan: PlanView? = null,
  @get:JsonInclude(JsonInclude.Include.NON_NULL)
  val endsAt: Double? = null
)

data class TopicAverage(
  val topic: String,
  val average: Double,
  val count: Int
)

data class MissedCause(
  val key: String,
  val label: String,
  val group: String?,
  val count: Int
)

data class HistoryView(
  val entries: List<HistoryEntry>,
  /** Over scored incident and toolbox entries only; null when none is scored. */
  val average: Double?,
  val scoredCount: Int,
  /** Weakest topic first. */
  val byTopic: List<TopicAverage>,
  val mostMissedCause: MissedCause?,
  /** A ramp scores the knee, the throughput and the resource, so it gets its own average. */
  val rampAverage: Double?,
  val rampCount: Int
)

data class LoadView(
  val multiplier: Double,
  val rps: Double
)

data class LabStatus(
  /** Null for a service that answers, else the error. */
  val services: Map<String, String?>,
  val active: ActiveView?,
  val loadMultiplier: Double,
  val baseRps: Int,
  val links: Map<String, String>
)

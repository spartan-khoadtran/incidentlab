package com.c0x12c.incidentlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.isEmpty
import kotlin.random.Random
import org.junit.jupiter.api.Test

class AnswerOptionsTest {

  /** Each scenario with the symptoms its tickets show over many draws, so a symptom that follows the params is covered both ways. */
  private val symptoms: Map<Scenario, Set<Symptom>> = ScenarioCatalog.all.associateWith { scenario ->
    val random = Random(SEED)
    (1..DRAWS).map { scenario.symptom(Params(scenario.params(random, ExerciseContext(cpuCount = 8, productDetailSeconds = 0.015)), "14:05")) }.toSet()
  }

  private val answerCauses = ScenarioCatalog.all.map { it.answer.cause }.toSet()

  @Test
  fun `every scenario offers its cause, its partial causes and every accepted signal`() {
    val problems = symptoms.flatMap { (scenario, seen) ->
      seen.flatMap { symptom ->
        val causes = Answers.causesFor(symptom).map { it.first }.toSet()
        val signals = Answers.signalsFor(symptom).map { it.first }.toSet()
        ((setOf(scenario.answer.cause) + scenario.answer.partial) - causes).map { "${scenario.id} ($symptom): cause '$it' is not offered" } +
          (scenario.answer.signal - signals).map { "${scenario.id} ($symptom): signal '$it' is not offered" }
      }
    }
    assertThat(problems).isEmpty()
  }

  @Test
  fun `every symptom offers a short list with real look-alikes and a distractor`() {
    val problems = Symptom.entries.flatMap { symptom ->
      val causes = Answers.causesFor(symptom).map { it.first }
      val signals = Answers.signalsFor(symptom)
      listOfNotNull(
        "$symptom offers ${causes.size} causes".takeIf { causes.size !in CAUSES_PER_SYMPTOM },
        "$symptom offers ${signals.size} signals".takeIf { signals.size !in SIGNALS_PER_SYMPTOM },
        "$symptom offers no distractor".takeIf { causes.none { it in Answers.DISTRACTORS } },
        "$symptom offers fewer than $MIN_REAL_CAUSES real causes".takeIf { causes.count { it in answerCauses } < MIN_REAL_CAUSES }
      )
    }
    assertThat(problems).isEmpty()
  }

  @Test
  fun `every cause, signal and symptom is used, and every cause has its own rule-out`() {
    val used = symptoms.values.flatten().toSet()
    val problems = Answers.CAUSES.filter { Answers.CAUSE_SYMPTOMS.getValue(it.first).isEmpty() }.map { "cause ${it.first} has no symptom" } +
      Answers.SIGNALS.filter { Answers.SIGNAL_SYMPTOMS.getValue(it.first).isEmpty() }.map { "signal ${it.first} has no symptom" } +
      (Symptom.entries - used).map { "no scenario has symptom $it" } +
      Answers.CAUSES.filter { (key, label) -> Answers.RULE_OUTS[key].isNullOrBlank() || Answers.RULE_OUTS[key] == label }.map { "cause ${it.first} has no rule-out of its own" } +
      (Answers.RULE_OUTS.keys - Answers.CAUSES.map { it.first }.toSet()).map { "rule-out for unknown cause $it" }
    assertThat(problems).isEmpty()
  }

  private companion object {
    const val SEED = 7
    const val DRAWS = 40
    const val MIN_REAL_CAUSES = 3
    val CAUSES_PER_SYMPTOM = 6..10
    val SIGNALS_PER_SYMPTOM = 4..8
  }
}

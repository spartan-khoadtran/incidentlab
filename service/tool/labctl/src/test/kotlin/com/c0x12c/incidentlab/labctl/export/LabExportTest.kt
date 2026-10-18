package com.c0x12c.incidentlab.labctl.export

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.Main
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Concepts
import com.c0x12c.incidentlab.labctl.quiz.Numbers
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.ExerciseContext
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LabExportTest {

  @TempDir
  lateinit var dir: Path

  private fun export(): Pair<String, JsonNode> {
    val out = dir.resolve("site/lab.json")
    assertThat(Main.run(listOf("export", out.toString()))).isEqualTo(0)
    val text = Files.readString(out)
    return text to LabJson.mapper.readTree(text)
  }

  @Test
  fun `a scenario card holds only what the catalog page shows`() {
    val (_, json) = export()
    val cards = json["scenarios"].toList()
    assertThat(cards.map { it["id"].asText() }).isEqualTo(ScenarioCatalog.all.map { it.id })
    for (card in cards) {
      assertThat(card.fieldNames().asSequence().toSet()).containsOnly("id", "title", "level", "topics", "doc", "doc_file", "toolbox")
    }
  }

  @Test
  fun `no answer field appears anywhere in the export`() {
    val (_, json) = export()
    val answerKeys = setOf(
      "answer", "nature", "location", "signal", "cause", "partial", "ticket", "hints", "steps", "explain", "evidence", "cli",
      "faults", "params", "marker", "decoy"
    )
    assertThat(keys(json).filter { it in answerKeys }).isEmpty()
  }

  @Test
  fun `no cause or signal key appears, except as the id of a scenario`() {
    val (text, json) = export()
    val ids = ScenarioCatalog.all.map { it.id }.toSet()
    val withoutIds = json["scenarios"].fold(text) { acc, card -> acc.replace("\"id\" : \"${card["id"].asText()}\"", "") }
    val keys = (Answers.CAUSES + Answers.SIGNALS).map { it.first }.filter { it !in ids && it.contains('_') }
    val leaked = keys.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(withoutIds) }
    assertThat(leaked).isEmpty()
    val labels = (Answers.CAUSES + Answers.SIGNALS + Answers.NATURES + Answers.LOCATIONS).map { it.second }.filter { it in text }
    assertThat(labels).isEmpty()
  }

  @Test
  fun `no ticket, hint, step, explanation, evidence or proof text appears`() {
    val (text, _) = export()
    val leaked = ScenarioCatalog.all.flatMap { scenario ->
      val params = Params(scenario.params(Random(1), ExerciseContext()), "14:05")
      val secrets = listOf(scenario.ticket(params), scenario.explain(params)) + scenario.hints + scenario.steps + scenario.evidence + scenario.cli
      secrets.filter { it.isNotBlank() && it in text }.map { "${scenario.id}: ${it.take(60)}" }
    }
    assertThat(leaked).isEmpty()
  }

  @Test
  fun `quiz counts add up to every drill, and every pointer has a file`() {
    val export = LabExport.build()
    assertThat(export.quiz.sumOf { it.numbers }).isEqualTo(Numbers.generators.size)
    assertThat(export.quiz.sumOf { it.concepts }).isEqualTo(Concepts.all.size)
    assertThat(export.quiz.map { it.file }.filter { it !in GuideIndex.fileOf.values }).isEmpty()
    assertThat(export.scenarios.filter { it.docFile == null }.map { it.id }).isEmpty()
    export.modes.forEach { assertThat(it.docFile).isNotNull() }
  }

  @Test
  fun `export without a path prints the usage`() {
    assertThat(Main.run(listOf("export"))).isEqualTo(2)
  }

  private fun keys(node: JsonNode): List<String> = when {
    node.isObject -> node.fields().asSequence().flatMap { (key, value) -> sequenceOf(key) + keys(value) }.toList()
    node.isArray -> node.flatMap { keys(it) }
    else -> emptyList()
  }
}

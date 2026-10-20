package com.c0x12c.incidentlab.labctl.api

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakeAnnotator
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.bench.BenchEngine
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.quiz.QuizSessions
import com.c0x12c.incidentlab.labctl.ramp.RampEngine
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.scenario.Symptom
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Opens every scenario through the API and checks that nothing a learner sees before answering names the answer.
 * The offered options hold the answer among the others, so they must depend on the ticket's symptom only.
 */
class NoSpoilerTest {

  private val client = HttpClient.newHttpClient()

  @Test
  fun `an open exercise shows no id, title, answer, explanation, step, evidence, command, guide pointer or rule-out, and offers only its symptom's lists`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    val probe = FakePrometheus { _, _ -> null }
    val annotator = FakeAnnotator()
    val zone = ZoneId.of("UTC")
    val ramp = RampEngine(store, probe, annotator, now = { NOW }, zone = zone)
    val bench = BenchEngine(store, probe, annotator, now = { NOW }, zone = zone)
    val exercises = ExerciseEngine(store, probe, annotator, random = Random(SEED), now = { NOW }, zone = zone, modes = listOf(ramp, bench))
    LabApi(exercises, ramp, bench, QuizSessions()).start(0).use { server ->
      val base = "http://127.0.0.1:${server.port}"
      val offeredBySymptom = mutableMapOf<Symptom, MutableSet<JsonNode>>()
      val leaks = ScenarioCatalog.all.flatMap { scenario ->
        listOf("incident", "toolbox").filter { it == "incident" || scenario.cli.isNotEmpty() }.flatMap { mode ->
          val opened = post("$base/api/exercises", """{"id": "${scenario.id}", "mode": "$mode", "force": true}""")
          assertThat(opened.statusCode()).isEqualTo(201)
          val raw = listOf(opened.body(), get("$base/api/exercise"), get("$base/api/status"))
          val views = raw.map { LabJson.mapper.readTree(it) }.map { it.path("active").takeIf(JsonNode::isObject) ?: it }
          val params = Params(store.load().active!!.params, Format.hhmm(NOW, zone))
          val symptom = scenario.symptom(params)
          val offered = views.map { it["options"] }
          assertThat(offered.toSet()).isEqualTo(setOf(expected(symptom)))
          offeredBySymptom.getOrPut(symptom) { mutableSetOf() } += offered
          val seen = raw.map(::withoutOptions)
          val found = secrets(scenario, params).filter { secret -> seen.any { secret.containsMatchIn(it) } }.map { it.pattern.take(80) } +
            ruleOutLeaks(raw, seen)
          found.map { "${scenario.id} ($mode): $it" }
        }
      }
      assertThat(leaks).isEmpty()
      assertThat(offeredBySymptom.filterValues { it.size > 1 }.keys).isEmpty()
    }
  }

  /** The offered lists must hold the answer, so the scan for the answer runs on everything else. */
  private fun withoutOptions(body: String): String {
    val node = LabJson.mapper.readTree(body)
    (node as? ObjectNode)?.remove("options")
    (node.path("active") as? ObjectNode)?.remove("options")
    return LabJson.mapper.writeValueAsString(node)
  }

  /** No rule-out shows before the answer: no field for it, and no check text, except one that is also an option's label. */
  private fun ruleOutLeaks(raw: List<String>, withoutOptions: List<String>): List<String> {
    val labels = (Answers.NATURES + Answers.LOCATIONS + Answers.SIGNALS + Answers.CAUSES).map { it.second }.toSet()
    val field = listOf("rule_outs").filter { name -> raw.any { it.contains("\"$name\"") } }
    val checks = Answers.RULE_OUTS.values.distinct().filter { check ->
      val text = json(check)
      withoutOptions.any { it.contains(text) } || (check !in labels && raw.any { it.contains(text) })
    }
    return field + checks.map { "rule-out check: ${it.take(60)}" }
  }

  private fun expected(symptom: Symptom): JsonNode =
    LabJson.mapper.valueToTree(
      mapOf(
        "natures" to Answers.NATURES.map { (key, label) -> mapOf("key" to key, "label" to label) },
        "locations" to Answers.LOCATIONS.map { (key, label) -> mapOf("key" to key, "label" to label) },
        "signals" to Answers.signalsFor(symptom).map { (key, label) -> mapOf("key" to key, "label" to label) },
        "causes" to Answers.causesFor(symptom).map { (key, label) -> mapOf("key" to key, "label" to label, "group" to Answers.CAUSE_GROUPS.getValue(key)) }
      )
    )

  @Test
  fun `an exercise opened before the key existed does not show its id through the key`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")
    Files.writeString(path, """{"active": {"id": "rtt_amplified", "params": {"delay": 5, "k": 9}, "started": $NOW, "n": 2}}""")
    val store = StateStore(path)
    val probe = FakePrometheus { _, _ -> null }
    val annotator = FakeAnnotator()
    val exercises = ExerciseEngine(store, probe, annotator, now = { NOW }, zone = ZoneId.of("UTC"))
    LabApi(exercises, RampEngine(store, probe, annotator), BenchEngine(store, probe, annotator), QuizSessions()).start(0).use { server ->
      val base = "http://127.0.0.1:${server.port}"
      val bodies = listOf(get("$base/api/exercise"), get("$base/api/status"))
      val keys = bodies.map { LabJson.mapper.readTree(it) }.map { (it.path("active").takeIf(JsonNode::isObject) ?: it)["key"].asText() }

      assertThat(bodies.map(::withoutOptions).none { it.contains("rtt_amplified") }).isTrue()
      assertThat(keys.all { it.startsWith("legacy-2-") }).isTrue()
    }
  }

  private fun secrets(scenario: Scenario, params: Params): List<Regex> {
    val answer = scenario.answer
    // A key with no underscore is a plain word the lab shows anyway: a service name (api, pricing) or "load".
    val keys = listOf(scenario.id) + (listOf(answer.cause) + answer.partial + answer.signal + answer.nature + answer.location).filter { '_' in it }
    val labels = answer.nature.map { Answers.label(Answers.NATURES, it) } + answer.location.map { Answers.label(Answers.LOCATIONS, it) } +
      answer.signal.map { Answers.label(Answers.SIGNALS, it) } + (listOf(answer.cause) + answer.partial).map { Answers.label(Answers.CAUSES, it) }
    val pointers = scenario.doc.split("; ").flatMap { listOf(it, it.replace(NOTE, "").trim()) } + scenario.doc
    val texts = listOf(scenario.title, scenario.explain(params)) + labels + scenario.steps + scenario.evidence + scenario.cli + pointers
    return keys.distinct().map { Regex("""\b${Regex.escape(it)}\b""") } + texts.filter { it.isNotBlank() }.distinct().map { Regex(Regex.escape(json(it))) }
  }

  /** How the text looks inside a JSON string, so quotes and line breaks still match. */
  private fun json(text: String): String = LabJson.mapper.writeValueAsString(text).removeSurrounding("\"")

  private fun get(url: String): String = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

  private fun post(url: String, body: String): HttpResponse<String> =
    client.send(
      HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("X-Lab-Client", "web").build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private companion object {
    const val NOW = 1_700_000_000.0
    const val SEED = 4
    val NOTE = Regex("""\s*\([^)]*\)$""")
  }
}

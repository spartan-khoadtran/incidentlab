package com.c0x12c.incidentlab.labctl.api

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.ZoneId
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Opens every scenario through the API and checks that nothing a learner sees before answering names the answer. */
class NoSpoilerTest {

  private val client = HttpClient.newHttpClient()

  @Test
  fun `an open exercise shows no id, title, answer, explanation, step, evidence, command or guide pointer`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    val probe = FakePrometheus { _, _ -> null }
    val annotator = FakeAnnotator()
    val zone = ZoneId.of("UTC")
    val ramp = RampEngine(store, probe, annotator, now = { NOW }, zone = zone)
    val bench = BenchEngine(store, probe, annotator, now = { NOW }, zone = zone)
    val exercises = ExerciseEngine(store, probe, annotator, random = Random(SEED), now = { NOW }, zone = zone, modes = listOf(ramp, bench))
    LabApi(exercises, ramp, bench, QuizSessions()).start(0).use { server ->
      val base = "http://127.0.0.1:${server.port}"
      val leaks = ScenarioCatalog.all.flatMap { scenario ->
        listOf("incident", "toolbox").filter { it == "incident" || scenario.cli.isNotEmpty() }.flatMap { mode ->
          val opened = post("$base/api/exercises", """{"id": "${scenario.id}", "mode": "$mode", "force": true}""")
          assertThat(opened.statusCode()).isEqualTo(201)
          val seen = listOf(opened.body(), get("$base/api/exercise"), get("$base/api/status"))
          val params = Params(store.load().active!!.params, Format.hhmm(NOW, zone))
          secrets(scenario, params).filter { secret -> seen.any { secret.containsMatchIn(it) } }.map { "${scenario.id} ($mode): ${it.pattern.take(80)}" }
        }
      }
      assertThat(leaks).isEmpty()
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

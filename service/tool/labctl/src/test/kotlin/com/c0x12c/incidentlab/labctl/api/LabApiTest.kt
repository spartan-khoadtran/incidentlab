package com.c0x12c.incidentlab.labctl.api

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.FakeAnnotator
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.SettleValues
import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.ScriptedTerminal
import com.c0x12c.incidentlab.labctl.bench.BenchEngine
import com.c0x12c.incidentlab.labctl.control.Capabilities
import com.c0x12c.incidentlab.labctl.exercise.AnswerOptions
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.SettleGate
import com.c0x12c.incidentlab.labctl.exercise.NewOptions
import com.c0x12c.incidentlab.labctl.exercise.Option
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.quiz.QuizSessions
import com.c0x12c.incidentlab.labctl.ramp.RampCatalog
import com.c0x12c.incidentlab.labctl.ramp.RampEngine
import com.c0x12c.incidentlab.labctl.ramp.RampPlan
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import com.c0x12c.incidentlab.labctl.scenario.Symptom
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.fasterxml.jackson.databind.JsonNode
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LabApiTest {

  @TempDir
  lateinit var dir: Path

  private var clock = START
  private val annotator = FakeAnnotator()
  private val annotations get() = annotator.texts
  private val servers = mutableListOf<ApiServer>()
  private val client: HttpClient = HttpClient.newHttpClient()

  /** A pool of 3 as Prometheus would record it during a ramp, and a bench held at 267 rps. */
  private val prometheus = FakePrometheus { expr, at ->
    val step = ((at - START) / EVERY).roundToInt() - 1
    val ramp = step in 0..7
    when {
      ramp && expr.startsWith("histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[50s]") ->
        listOf(0.080, 0.085, 0.090, 0.110, 0.180, 0.420, 1.900, 2.000)[step]
      ramp && expr.contains("status=~\"2..\"") -> listOf(50.0, 75.0, 100.0, 125.0, 149.0, 196.0, 203.0, 201.0)[step]
      ramp && expr.contains("outcome!~") && expr.contains("[50s]") -> listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.002, 0.06, 0.25)[step]
      ramp && expr.startsWith("sum(rate(client_requests_total[50s]") -> RampPlan.rps(step).toDouble()
      expr.startsWith("sum(rate(client_requests_total[") -> if (at > BENCH_START) 267.0 else 50.0
      expr.startsWith("sum(rate(client_duration_seconds_sum") -> 0.075
      expr.startsWith("avg_over_time(http_server_inflight") -> 19.6
      else -> null
    }
  }

  private fun store(name: String = "state.json") = StateStore(dir.resolve(name))

  private fun server(
    probe: FakePrometheus = prometheus,
    store: StateStore = store(),
    capabilities: Capabilities = Capabilities.ALL,
    offer: (Symptom) -> AnswerOptions = { ExerciseEngine.answerOptions(it) },
    random: Random = Random(1),
    gate: SettleGate? = null
  ): String {
    val ramp = RampEngine(store, probe, annotator, Random(1), { clock }, ZONE, gate)
    val bench = BenchEngine(store, probe, annotator, { clock }, ZONE, gate)
    val exercises = ExerciseEngine(store, probe, annotator, random = random, now = { clock }, zone = ZONE, modes = listOf(ramp, bench), capabilities = capabilities, offer = offer, gate = gate)
    val quiz = QuizSessions(Random(2))
    val server = LabApi(exercises, ramp, bench, quiz).start(0)
    servers += server
    return "http://127.0.0.1:${server.port}"
  }

  @AfterEach
  fun stop() {
    servers.forEach { it.close() }
  }

  private class Answer(val status: Int, val json: JsonNode, val headers: Map<String, List<String>>)

  private fun send(request: HttpRequest): Answer {
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return Answer(response.statusCode(), LabJson.mapper.readTree(response.body()), response.headers().map())
  }

  private fun get(base: String, path: String): Answer = send(HttpRequest.newBuilder(URI.create(base + path)).GET().build())

  private fun post(base: String, path: String, body: Any? = null, headers: Map<String, String> = WEB): Answer {
    val builder = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body?.let { LabJson.mapper.writeValueAsString(it) } ?: ""))
    headers.forEach { (name, value) -> builder.header(name, value) }
    return send(builder.build())
  }

  private fun keys(node: JsonNode): Set<String> = node.fieldNames().asSequence().toSet()

  @Test
  fun `health answers JSON and never sends a CORS header`() {
    val base = server()
    val health = get(base, "/api/health")

    assertThat(health.status).isEqualTo(200)
    assertThat(health.json["ok"].asBoolean()).isTrue()
    assertThat(health.headers.getValue("content-type").single()).isEqualTo("application/json; charset=utf-8")
    assertThat(health.headers.keys.filter { it.lowercase().startsWith("access-control") }).isEmpty()
  }

  @Test
  fun `a write without the client header or the JSON type is forbidden, and so is OPTIONS`() {
    val base = server()

    assertThat(post(base, "/api/exercise/clear", headers = mapOf("Content-Type" to "application/json")).status).isEqualTo(403)
    assertThat(post(base, "/api/exercise/clear", headers = mapOf("X-Lab-Client" to "web", "Content-Type" to "text/plain")).status).isEqualTo(403)
    assertThat(post(base, "/api/exercise/clear", headers = mapOf("X-Lab-Client" to "other", "Content-Type" to "application/json")).status).isEqualTo(403)
    val forbidden = post(base, "/api/exercises", mapOf("id" to "oom"), headers = mapOf("Content-Type" to "application/json"))
    assertThat(forbidden.json["error"].asText()).isEqualTo("forbidden")
    assertThat(store().load().active).isNull()
    val options = send(HttpRequest.newBuilder(URI.create("$base/api/exercises")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build())
    assertThat(options.status).isEqualTo(403)
    assertThat(options.headers.keys.filter { it.lowercase().startsWith("access-control") }).isEmpty()
    assertThat(post(base, "/api/exercise/clear", headers = mapOf("X-Lab-Client" to "web", "Content-Type" to "application/json; charset=utf-8")).status).isEqualTo(200)
  }

  @Test
  fun `a request for another host name is refused, so a rebound DNS name cannot drive the lab`() {
    val port = URI.create(server()).port

    assertThat(statusFor(port, "evil.example:4000")).isEqualTo(403)
    assertThat(statusFor(port, "192.168.1.20:4000")).isEqualTo(403)
    assertThat(statusFor(port, "localhost:4000")).isEqualTo(200)
    assertThat(statusFor(port, "127.0.0.1")).isEqualTo(200)
    assertThat(statusFor(port, "[::1]:4000")).isEqualTo(200)
    assertThat(statusFor(port, "control:8090")).isEqualTo(200)
  }

  private fun statusFor(port: Int, host: String): Int =
    Socket("127.0.0.1", port).use { socket ->
      socket.getOutputStream().write("GET /api/health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
      socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
    }

  @Test
  fun `unknown paths, wrong methods, broken JSON and big bodies get a JSON error`() {
    val base = server()

    assertThat(get(base, "/api/nope").json["error"].asText()).isEqualTo("not_found")
    val wrongMethod = get(base, "/api/exercises")
    assertThat(wrongMethod.status).isEqualTo(405)
    assertThat(wrongMethod.json["error"].asText()).isEqualTo("method_not_allowed")
    val broken = send(HttpRequest.newBuilder(URI.create("$base/api/load")).POST(HttpRequest.BodyPublishers.ofString("{oops")).headers(*WEB_HEADERS).build())
    assertThat(broken.status).isEqualTo(400)
    assertThat(broken.json["error"].asText()).isEqualTo("bad_json")
    val wrongType = post(base, "/api/load", mapOf("multiplier" to listOf(1)))
    assertThat(wrongType.json["message"].asText()).isEqualTo("The field 'multiplier' has the wrong type.")
    val big = post(base, "/api/load", mapOf("multiplier" to 1, "pad" to "x".repeat(ApiServer.MAX_BODY_BYTES)))
    assertThat(big.status).isEqualTo(413)
    assertThat(big.json["error"].asText()).isEqualTo("too_large")
  }

  @Test
  fun `a new exercise opens once, shows only its view, and needs force to replace it`() {
    val base = server()

    val opened = post(base, "/api/exercises", mapOf("id" to "pool_hold"))
    assertThat(opened.status).isEqualTo(201)
    assertThat(keys(opened.json)).containsOnly(*ACTIVE_VIEW_KEYS)
    assertThat(keys(opened.json["options"])).containsOnly("natures", "locations", "signals", "causes")
    assertThat(opened.json["n"].asInt()).isEqualTo(1)
    assertThat(opened.json["level"].asInt()).isEqualTo(ScenarioCatalog.byId.getValue("pool_hold").level)
    assertThat(opened.json["ticket"].asText().contains("22:13")).isTrue()
    assertThat(opened.json["rules"].isNull).isTrue()
    assertThat(annotations.any { it.startsWith("Deploy checkout-api v2.31") }).isTrue()
    assertThat(get(base, "/api/exercise").json).isEqualTo(opened.json)

    val again = post(base, "/api/exercises", mapOf("id" to "oom"))
    assertThat(again.status).isEqualTo(409)
    assertThat(again.json["error"].asText()).isEqualTo("exercise_open")
    assertThat(post(base, "/api/exercises", mapOf("mode" to "toolbox", "force" to true)).json["rules"].asText()).isEqualTo(Exercises.TOOLBOX_RULES)
  }

  @Test
  fun `a start waits for the lab to settle, and the next baseline begins where the last exercise ended`() {
    var lag = 30.0
    val healthy = mapOf(SettleGate.P99 to 0.1, SettleGate.ERRORS to 0.0, SettleGate.WAITING to 0.0)
    val values = SettleValues(worst = { if (it == SettleGate.LAG) lag else healthy[it] }, normal = { healthy[it] })
    val probe = FakePrometheus { expr, _ -> if (expr == SettleGate.UPTIME) 600.0 else values.answer(expr) }
    val base = server(probe = probe, gate = SettleGate(probe) { clock })

    assertThat(post(base, "/api/exercises", mapOf("id" to "pool_hold")).status).isEqualTo(201)
    val ended = clock
    val waiting = post(base, "/api/exercises", mapOf("id" to "oom", "force" to true))
    assertThat(waiting.status).isEqualTo(409)
    assertThat(waiting.json["error"].asText()).isEqualTo("lab_settling")
    assertThat(keys(waiting.json["settle"])).containsOnly("settled", "checks", "waited_seconds", "gave_up")
    assertThat(waiting.json["settle"]["checks"].filter { !it["ok"].asBoolean() }.map { it["name"].asText() })
      .containsOnly("Time since the last exercise ended", "Kafka lag")
    assertThat(get(base, "/api/exercise").json["error"].asText()).isEqualTo("no_exercise")
    assertThat(post(base, "/api/ramp", mapOf("every" to 60)).json["error"].asText()).isEqualTo("lab_settling")
    assertThat(post(base, "/api/bench", emptyMap<String, Any>()).json["error"].asText()).isEqualTo("lab_settling")

    clock += 61
    lag = 0.0
    assertThat(get(base, "/api/settle").json["settled"].asBoolean()).isTrue()
    val opened = post(base, "/api/exercises", mapOf("id" to "oom"))
    assertThat(opened.status).isEqualTo(201)
    assertThat(opened.json["baseline_from"].asDouble()).isEqualTo(ended)
  }

  @Test
  fun `a ramp or a bench that starts before the lab settled says which checks were off`() {
    val values = SettleValues(worst = { if (it == SettleGate.LAG) 30.0 else null })
    val probe = FakePrometheus { expr, _ -> if (expr == SettleGate.UPTIME) 600.0 else values.answer(expr) }

    val ramp = post(server(probe = probe, gate = SettleGate(probe) { clock }), "/api/ramp", mapOf("every" to 60))
    val bench = post(server(probe = probe, store = store("bench.json"), gate = SettleGate(probe) { clock }), "/api/bench", emptyMap<String, Any>())

    assertThat(ramp.status).isEqualTo(201)
    assertThat(ramp.json["unsettled"].map { it.asText() }).containsOnly("Kafka lag")
    assertThat(bench.status).isEqualTo(201)
    assertThat(bench.json["unsettled"].map { it.asText() }).containsOnly("Kafka lag")
  }

  @Test
  fun `with no gate the lab is always settled`() {
    val settle = get(server(), "/api/settle").json
    assertThat(settle["settled"].asBoolean()).isTrue()
    assertThat(settle["checks"].size()).isEqualTo(0)
  }

  @Test
  fun `bad options are refused before anything starts`() {
    val base = server()
    val noCli = ScenarioCatalog.all.first { it.cli.isEmpty() }.id

    assertThat(post(base, "/api/exercises", mapOf("level" to 9)).json["error"].asText()).isEqualTo("bad_level")
    assertThat(post(base, "/api/exercises", mapOf("topic" to "astrology")).json["error"].asText()).isEqualTo("bad_topic")
    assertThat(post(base, "/api/exercises", mapOf("mode" to "ramp")).json["error"].asText()).isEqualTo("bad_mode")
    assertThat(post(base, "/api/exercises", mapOf("id" to "nope")).json["error"].asText()).isEqualTo("unknown_id")
    val toolbox = post(base, "/api/exercises", mapOf("id" to noCli, "mode" to "toolbox"))
    assertThat(toolbox.status).isEqualTo(400)
    assertThat(toolbox.json["error"].asText()).isEqualTo("no_cli")
    assertThat(store().load().active).isNull()
  }

  @Test
  fun `an id the controller cannot run is a 409 that names the missing settings`() {
    val base = server(capabilities = Capabilities { setOf("cpus") })

    val refused = post(base, "/api/exercises", mapOf("id" to "packet_loss"))
    assertThat(refused.status).isEqualTo(409)
    assertThat(refused.json["error"].asText()).isEqualTo("unsupported")
    assertThat(refused.json["missing"].map { it.asText() }).containsExactly("netem")
    assertThat(store().load().active).isNull()
    assertThat(post(base, "/api/exercises", mapOf("id" to "cpu_throttle")).status).isEqualTo(201)
  }

  @Test
  fun `away from load 1 an id tuned for the base load is a 409`() {
    val base = server()
    val baseLoad = ScenarioCatalog.all.first { it.baseLoad }.id
    post(base, "/api/load", mapOf("multiplier" to 1.5))

    val refused = post(base, "/api/exercises", mapOf("id" to baseLoad))
    assertThat(refused.status).isEqualTo(409)
    assertThat(refused.json["error"].asText()).isEqualTo("load_not_one")
    assertThat(store().load().active).isNull()
  }

  @Test
  fun `with no exercise every exercise call is a 404`() {
    val base = server()

    assertThat(get(base, "/api/exercise").json["error"].asText()).isEqualTo("no_exercise")
    for (path in listOf("hint", "reveal")) {
      assertThat(post(base, "/api/exercise/$path").status).isEqualTo(404)
    }
    assertThat(post(base, "/api/exercise/answer", choices("arch", "api", "pool", "pool_exhausted")).status).isEqualTo(404)
    assertThat(get(base, "/api/status").json["active"].isNull).isTrue()
  }

  @Test
  fun `hints come one at a time until none is left`() {
    val base = server()
    val scenario = ScenarioCatalog.byId.getValue("cpu_throttle")
    post(base, "/api/exercises", mapOf("id" to scenario.id))

    repeat(scenario.hints.size) { taken ->
      val view = post(base, "/api/exercise/hint").json
      assertThat(view["hints_used"].asInt()).isEqualTo(taken + 1)
      assertThat(view["hints"].map { it.asText() }).isEqualTo(scenario.hints.take(taken + 1))
    }
    val none = post(base, "/api/exercise/hint")
    assertThat(none.status).isEqualTo(409)
    assertThat(none.json["error"].asText()).isEqualTo("no_hints_left")
  }

  @Test
  fun `an answer is scored, kept in history with its note and picks, and leaves the fault running`() {
    val base = server()
    post(base, "/api/exercises", mapOf("id" to "pool_hold"))
    post(base, "/api/exercise/hint")

    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "nope", "pool_exhausted")).json["error"].asText()).isEqualTo("unknown_key")
    assertThat(post(base, "/api/exercise/answer", mapOf("nature" to "arch")).json["error"].asText()).isEqualTo("missing_field")
    val result = post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", "pool_exhausted") + ("note" to "session time\nvs exec time")).json

    assertThat(result["score"].asDouble()).isEqualTo(4.5)
    assertThat(result["raw"].asDouble()).isEqualTo(5.0)
    assertThat(result["verdicts"]["cause"].asText()).isEqualTo("correct")
    assertThat(result["reveal"]["id"].asText()).isEqualTo("pool_hold")
    assertThat(result["reveal"]["guide"].all { it["file"].isTextual }).isTrue()
    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", "pool_exhausted")).json["error"].asText()).isEqualTo("already_answered")
    val after = get(base, "/api/exercise").json
    assertThat(after["answered"].asBoolean()).isTrue()
    assertThat(after["reveal"]["title"].asText()).isEqualTo(ScenarioCatalog.byId.getValue("pool_hold").title)
    assertThat(after["result"]).isEqualTo(result)
    assertThat(after["result"]["choices"]).isEqualTo(LabJson.mapper.valueToTree<JsonNode>(choices("arch", "api_db", "pool", "pool_exhausted")))
    assertThat(store().load().active!!.answered).isTrue()
    assertThat(store().load().history.single().note).isEqualTo("session time vs exec time")
    assertThat(annotations.none { it == "lab: fault cleared" }).isTrue()

    assertThat(post(base, "/api/exercise/clear").json["cleared"].asBoolean()).isTrue()
    assertThat(annotations.last()).isEqualTo("lab: fault cleared")
    assertThat(store().load().active).isNull()
  }

  @Test
  fun `an answer key the exercise did not offer is refused, even when it exists, and the rule-outs list only what was offered`() {
    val expected = ScenarioCatalog.byId.getValue("pool_hold").answer
    val causes = setOf(expected.cause) + expected.partial
    val narrow = { symptom: Symptom ->
      ExerciseEngine.answerOptions(symptom).let { all -> all.copy(signals = all.signals.filter { it.key in expected.signal }, causes = all.causes.filter { it.key in causes }) }
    }
    val base = server(offer = narrow)
    val opened = post(base, "/api/exercises", mapOf("id" to "pool_hold")).json
    val notOffered = Answers.CAUSES.first { it.first !in causes }.first

    assertThat(opened["options"]["signals"].map { it["key"].asText() }).isEqualTo(Answers.SIGNALS.map { it.first }.filter { it in expected.signal })
    assertThat(opened["options"]["causes"].map { it["key"].asText() }).isEqualTo(Answers.CAUSES.map { it.first }.filter { it in causes })
    post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", notOffered)).let {
      assertThat(it.status).isEqualTo(400)
      assertThat(it.json["error"].asText()).isEqualTo("unknown_key")
      assertThat(it.json["message"].asText()).isEqualTo("'$notOffered' is not a cause this exercise offers.")
    }
    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "host_cpu", expected.cause)).json["error"].asText()).isEqualTo("unknown_key")
    assertThat(store().load().active!!.answered).isFalse()
    assertThat(store().load().history).isEmpty()

    val result = post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", expected.cause)).json
    assertThat(result["score"].asDouble()).isEqualTo(5.0)
    val ruleOuts = result["reveal"]["rule_outs"]
    assertThat(ruleOuts.map { it["cause"]["key"].asText() }).isEqualTo(opened["options"]["causes"].map { it["key"].asText() })
    assertThat(ruleOuts.filter { it["correct"].asBoolean() }.map { it["cause"]["key"].asText() }).containsExactly(expected.cause)
    assertThat(ruleOuts.filter { it["partial"].asBoolean() }.map { it["cause"]["key"].asText() })
      .isEqualTo(opened["options"]["causes"].map { it["key"].asText() }.filter { it in expected.partial })
    assertThat(ruleOuts.all { it["check"].asText() == Answers.RULE_OUTS.getValue(it["cause"]["key"].asText()) }).isTrue()
    assertThat(ruleOuts.all { it["cause"]["group"].asText() == Answers.CAUSE_GROUPS.getValue(it["cause"]["key"].asText()) }).isTrue()
  }

  @Test
  fun `a real cause the exercise does not offer is a 400 unknown_key`() {
    val base = server()
    val (opened, notOffered) = ScenarioCatalog.all.firstNotNullOf { scenario ->
      val options = post(base, "/api/exercises", mapOf("id" to scenario.id, "force" to true)).json["options"]
      val offered = options["causes"].map { it["key"].asText() }.toSet()
      Answers.CAUSES.map { it.first }.firstOrNull { it !in offered }?.let { options to it }
    }
    val first = { question: String -> opened[question][0]["key"].asText() }

    val refused = post(base, "/api/exercise/answer", choices(first("natures"), first("locations"), first("signals"), notOffered))

    assertThat(refused.status).isEqualTo(400)
    assertThat(refused.json["error"].asText()).isEqualTo("unknown_key")
    assertThat(refused.json["message"].asText()).isEqualTo("'$notOffered' is not a cause this exercise offers.")
    assertThat(store().load().active!!.answered).isFalse()
    assertThat(store().load().history).isEmpty()
  }

  @Test
  fun `a write that names an exercise which is no longer open is a 409 and changes nothing`() {
    val base = server()
    val first = post(base, "/api/exercises", mapOf("id" to "pool_hold")).json["key"].asText()
    val second = post(base, "/api/exercises", mapOf("id" to "oom", "force" to true)).json["key"].asText()
    assertThat(second == first).isFalse()
    assertThat(get(base, "/api/status").json["active"]["key"].asText()).isEqualTo(second)

    for ((path, body) in listOf("hint" to emptyMap(), "answer" to choices("arch", "api_db", "pool", "pool_exhausted"), "reveal" to emptyMap(), "clear" to emptyMap())) {
      val stale = post(base, "/api/exercise/$path", body + ("key" to first))
      assertThat(stale.status).isEqualTo(409)
      assertThat(stale.json["error"].asText()).isEqualTo("exercise_changed")
    }
    val state = store().load()
    assertThat(state.active!!.id).isEqualTo("oom")
    assertThat(state.active!!.answered).isFalse()
    assertThat(state.active!!.hints).isEqualTo(0)
    assertThat(state.history).isEmpty()

    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", "pool_exhausted") + ("key" to second)).status).isEqualTo(200)
    assertThat(post(base, "/api/exercise/clear", mapOf("key" to second)).json["cleared"].asBoolean()).isTrue()
    assertThat(store().load().active).isNull()
    assertThat(post(base, "/api/exercise/clear").json["cleared"].asBoolean()).isFalse()
    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", "pool_exhausted") + ("key" to second)).json["error"].asText()).isEqualTo("exercise_changed")
  }

  @Test
  fun `a ramp answer, reveal or bench report for another exercise is a 409`() {
    val base = server()
    val ramp = post(base, "/api/ramp", mapOf("every" to EVERY)).json["key"].asText()
    clock = START + RampPlan.totalSeconds(EVERY) + 30
    post(base, "/api/exercise/clear", mapOf("key" to ramp))
    clock = BENCH_START
    val bench = post(base, "/api/bench", mapOf("clients" to 20, "seconds" to 120)).json["key"].asText()
    clock = BENCH_START + 200

    post(base, "/api/ramp/answer", rampAnswer(200, 205, "pool") + ("key" to ramp)).let {
      assertThat(it.status).isEqualTo(409)
      assertThat(it.json["error"].asText()).isEqualTo("exercise_changed")
    }
    post(base, "/api/ramp/reveal", mapOf("key" to ramp)).let {
      assertThat(it.status).isEqualTo(409)
      assertThat(it.json["error"].asText()).isEqualTo("exercise_changed")
    }
    post(base, "/api/bench/report", mapOf("key" to ramp)).let {
      assertThat(it.status).isEqualTo(409)
      assertThat(it.json["error"].asText()).isEqualTo("exercise_changed")
    }
    assertThat(store().load().active!!.key).isEqualTo(bench)
    assertThat(post(base, "/api/bench/report", mapOf("key" to bench)).status).isEqualTo(200)
    assertThat(store().load().active).isNull()
  }

  @Test
  fun `a load change during a ramp step is a 409 with the seconds to its end`() {
    val base = server()
    post(base, "/api/ramp", mapOf("every" to EVERY))
    clock = START + 100

    val refused = post(base, "/api/load", mapOf("multiplier" to 2))
    assertThat(refused.status).isEqualTo(409)
    assertThat(refused.json["error"].asText()).isEqualTo("ramp_running")
    assertThat(refused.json["seconds_left"].asInt()).isEqualTo(RampPlan.totalSeconds(EVERY) - 100)
    assertThat(get(base, "/api/status").json["load_multiplier"].asDouble()).isEqualTo(1.0)

    clock = START + RampPlan.totalSeconds(EVERY)
    assertThat(post(base, "/api/load", mapOf("multiplier" to 2)).status).isEqualTo(200)
  }

  @Test
  fun `an exercise answered before the picks were stored shows its reveal and no result`() {
    val base = server()
    post(base, "/api/exercises", mapOf("id" to "oom"))
    store().update { it.copy(active = it.active!!.copy(answered = true)) }

    val view = get(base, "/api/exercise").json
    assertThat(view["reveal"]["id"].asText()).isEqualTo("oom")
    assertThat(view.has("result")).isFalse()
  }

  @Test
  fun `an answer scores exactly what the CLI scores for the same picks`() {
    val cliStore = store("cli.json")
    val random = Random(9)
    val picked = ScenarioCatalog.all.shuffled(random).take(SCORED_SCENARIOS)
    for (scenario in picked) {
      repeat(PICKS_PER_SCENARIO) { round ->
        val hints = round % 2
        val terminal = ScriptedTerminal()
        val cli = Exercises(cliStore, prometheus, annotator, terminal, random = Random(SEED), now = { clock }, zone = ZONE)
        cli.newExercise(NewOptions(id = scenario.id))
        repeat(hints) { cli.hint() }
        val base = server(random = Random(SEED))
        val web = LabJson.mapper.treeToValue(post(base, "/api/exercises", mapOf("id" to scenario.id, "force" to true)).json["options"], AnswerOptions::class.java)
        repeat(hints) { post(base, "/api/exercise/hint") }

        val cliOptions = (ExerciseEngine(cliStore, prometheus, annotator, now = { clock }, zone = ZONE).active() as Outcome.Done).value.active.options!!
        assertThat(web).isEqualTo(cliOptions)
        val expected = scenario.answer
        val nature = if (round == 0) expected.nature.first() else cliOptions.natures.random(random).key
        val location = if (round == 0) expected.location.first() else cliOptions.locations.random(random).key
        val signal = if (round == 0) expected.signal.first() else cliOptions.signals.random(random).key
        val cause = listOf(expected.cause, expected.partial.firstOrNull() ?: expected.cause, cliOptions.causes.random(random).key)[round]

        terminal.queue(*(picks(cliOptions, nature, location, signal, cause) + listOf("", "y")).toTypedArray())
        cli.answer()
        val result = post(base, "/api/exercise/answer", choices(nature, location, signal, cause)).json
        post(base, "/api/exercise/clear")

        val entry = cliStore.load().history.last()
        assertThat(result["score"].asDouble()).isEqualTo(entry.score)
        assertThat(result["missed"].map { it.asText() }).isEqualTo(entry.missed)
        assertThat(store().load().history.last().copy(at = 0.0)).isEqualTo(entry.copy(at = 0.0))
      }
    }
  }

  @Test
  fun `reveal is not scored, and history adds up answers and reveals`() {
    val base = server()
    post(base, "/api/exercises", mapOf("id" to "retry_storm"))
    post(base, "/api/exercise/answer", choices("amplified", "client", "attempts_vs_requests", "downstream_errors"))
    post(base, "/api/exercises", mapOf("id" to "oom", "force" to true))

    val reveal = post(base, "/api/exercise/reveal").json
    assertThat(reveal["id"].asText()).isEqualTo("oom")
    assertThat(keys(reveal)).containsOnly("id", "title", "level", "topics", "answer", "explanation", "steps", "evidence", "cli", "guide", "rule_outs")
    assertThat(reveal["rule_outs"].map { it["cause"]["key"].asText() }).isEqualTo(get(base, "/api/exercise").json["options"]["causes"].map { it["key"].asText() })
    assertThat(reveal["rule_outs"].filter { it["correct"].asBoolean() }.map { it["cause"]["key"].asText() }).containsExactly("oom")
    assertThat(reveal["answer"]["cause"]["group"].asText()).isEqualTo(Answers.CAUSE_GROUPS.getValue("oom"))
    post(base, "/api/exercise/reveal")
    assertThat(post(base, "/api/exercise/hint").json["error"].asText()).isEqualTo("already_answered")

    val history = get(base, "/api/history").json
    assertThat(history["entries"].map { it["id"].asText() }).containsExactly("retry_storm", "oom")
    assertThat(history["entries"][1]["score"].isNull).isTrue()
    assertThat(history["average"].asDouble()).isEqualTo(4.0)
    assertThat(history["by_topic"].map { it["topic"].asText() }.toSet()).isEqualTo(ScenarioCatalog.byId.getValue("retry_storm").topics.toSet())
    assertThat(history["by_topic"].all { it["average"].asDouble() == 4.0 && it["count"].asInt() == 1 }).isTrue()
    assertThat(history["most_missed_cause"]["key"].asText()).isEqualTo("retry_storm")
    assertThat(history["most_missed_cause"]["count"].asInt()).isEqualTo(1)
  }

  @Test
  fun `load takes a multiplier inside the CLI range and status shows it`() {
    val base = server()

    assertThat(post(base, "/api/load", mapOf("multiplier" to 0.05)).json["error"].asText()).isEqualTo("bad_multiplier")
    assertThat(post(base, "/api/load", mapOf("multiplier" to 6.5)).status).isEqualTo(400)
    assertThat(post(base, "/api/load").status).isEqualTo(400)
    val load = post(base, "/api/load", mapOf("multiplier" to 1.5)).json
    assertThat(load["multiplier"].asDouble()).isEqualTo(1.5)
    assertThat(load["rps"].asDouble()).isEqualTo(75.0)

    val status = get(base, "/api/status").json
    assertThat(status["load_multiplier"].asDouble()).isEqualTo(1.5)
    assertThat(status["base_rps"].asInt()).isEqualTo(50)
    assertThat(status["services"]["api"].isNull).isTrue()
    assertThat(keys(status["links"])).containsOnly("grafana", "prometheus", "pyroscope")
  }

  @Test
  fun `options list the levels, topics and modes, and no answer keys`() {
    val options = get(server(), "/api/options").json

    assertThat(keys(options)).containsOnly("levels", "topics", "modes")
    assertThat(options["topics"].map { it.asText() }).isEqualTo(Answers.TOPICS)
    assertThat(options["modes"].map { it.asText() }).containsExactly("incident", "toolbox")
    assertThat(options["levels"].map { it.asInt() }).containsExactly(1, 2, 3)
  }

  @Test
  fun `a ramp starts at load 1, refuses an early answer, and scores from Prometheus`() {
    val base = server()
    post(base, "/api/load", mapOf("multiplier" to 2))
    assertThat(post(base, "/api/ramp", mapOf("every" to EVERY)).json["error"].asText()).isEqualTo("load_not_one")
    post(base, "/api/load", mapOf("multiplier" to 1))
    assertThat(post(base, "/api/ramp", mapOf("every" to 5)).json["error"].asText()).isEqualTo("bad_every")

    val started = post(base, "/api/ramp", mapOf("every" to EVERY))
    assertThat(started.status).isEqualTo(201)
    assertThat(started.json["mode"].asText()).isEqualTo("ramp")
    assertThat(started.json["level"].isNull).isTrue()
    assertThat(started.json.has("options")).isFalse()
    assertThat(started.json["plan"]["steps"].map { it.asInt() }).isEqualTo(RampPlan.rates)
    assertThat(started.json["plan"]["total_seconds"].asInt()).isEqualTo(RampPlan.totalSeconds(EVERY))
    pinPoolOfThree()
    assertThat(get(base, "/api/ramp/options").json["resources"].map { it["key"].asText() }).isEqualTo(RampCatalog.RESOURCES.map { it.first })
    clock = START + 90
    val running = get(base, "/api/exercise").json
    assertThat(running["plan"]).isEqualTo(started.json["plan"])
    assertThat(running["elapsed_seconds"].asDouble()).isEqualTo(90.0)
    assertThat(running.has("result")).isFalse()
    assertThat(post(base, "/api/exercise/hint").json["error"].asText()).isEqualTo("no_hints_left")
    assertThat(post(base, "/api/exercise/answer", choices("arch", "api_db", "pool", "pool_exhausted")).json["error"].asText()).isEqualTo("wrong_mode")

    clock = START + 3 * EVERY + 20
    val early = post(base, "/api/ramp/answer", rampAnswer(0, 100, "none"))
    assertThat(early.json["error"].asText()).isEqualTo("too_early")
    assertThat(early.json["seconds_left"].asInt()).isEqualTo(RampPlan.totalSeconds(EVERY) - 3 * EVERY - 20)

    clock = START + RampPlan.totalSeconds(EVERY) + 30
    assertThat(post(base, "/api/ramp/answer", rampAnswer(200, 205, "disk")).json["error"].asText()).isEqualTo("unknown_key")
    val result = post(base, "/api/ramp/answer", rampAnswer(200, 205, "pool") + ("note" to "p99 at each step")).json
    assertThat(result["score"].asDouble()).isEqualTo(5.0)
    assertThat(result["measured"]["knee_rps"].asInt()).isEqualTo(200)
    assertThat(result["measured"]["max_rps"].asDouble()).isEqualTo(203.0)
    assertThat(result["measured"]["resource"].asText()).isEqualTo("pool")
    assertThat(result["table"].size()).isEqualTo(RampPlan.size)
    assertThat(keys(result["table"][0])).containsOnly("step", "target_rps", "rps_offered", "rps_achieved", "p99", "error_share", "knee")
    assertThat(result["explanation"].asText().contains("pool_max = 3")).isTrue()
    assertThat(post(base, "/api/ramp/answer", rampAnswer(200, 205, "pool")).json["error"].asText()).isEqualTo("already_answered")
    clock += 600
    val reloaded = get(base, "/api/exercise").json
    assertThat(reloaded["result"]).isEqualTo(result)
    assertThat(reloaded["plan"]["every"].asInt()).isEqualTo(EVERY)
    val entry = store().load().history.single()
    assertThat(entry.mode).isEqualTo("ramp")
    assertThat(entry.score).isEqualTo(5.0)
    assertThat(entry.note).isEqualTo("p99 at each step")
  }

  @Test
  fun `a ramp answer with Prometheus down is a 503, and the reveal still shows the hidden limit`() {
    val base = server(FakePrometheus(up = false) { _, _ -> 1.0 })
    post(base, "/api/ramp", mapOf("every" to EVERY))
    clock = START + RampPlan.totalSeconds(EVERY)

    val down = post(base, "/api/ramp/answer", rampAnswer(100, 100, "cpu"))
    assertThat(down.status).isEqualTo(503)
    assertThat(down.json["error"].asText()).isEqualTo("prometheus_down")
    val reveal = post(base, "/api/ramp/reveal").json
    assertThat(reveal["score"].isNull).isTrue()
    assertThat(reveal["prometheus_down"].asBoolean()).isTrue()
    assertThat(reveal["table"].size()).isEqualTo(0)
    assertThat(store().load().history.single().score).isNull()
  }

  @Test
  fun `a bench reports only when it is over, and only a POST closes it`() {
    val base = server()
    clock = BENCH_START
    assertThat(post(base, "/api/bench", mapOf("clients" to 0)).json["error"].asText()).isEqualTo("bad_clients")
    val started = post(base, "/api/bench", mapOf("clients" to 20, "seconds" to 120))
    assertThat(started.status).isEqualTo(201)
    assertThat(started.json["ends_at"].asDouble()).isEqualTo(BENCH_START + 120)
    assertThat(started.json.has("options")).isFalse()
    assertThat(get(base, "/api/exercise").json["ends_at"].asDouble()).isEqualTo(BENCH_START + 120)
    assertThat(post(base, "/api/ramp").json["error"].asText()).isEqualTo("exercise_open")

    clock = BENCH_START + 50
    val running = get(base, "/api/bench/report")
    assertThat(running.status).isEqualTo(425)
    assertThat(running.json["seconds_left"].asInt()).isEqualTo(70)

    clock = BENCH_START + 200
    val report = get(base, "/api/bench/report").json
    assertThat(report["during"]["client"]["rate_rps"].asDouble()).isEqualTo(267.0)
    assertThat(report["before"]["client"]["rate_rps"].asDouble()).isEqualTo(50.0)
    assertThat(keys(report["during"])).containsOnly("client", "api", "client_mean", "api_in_flight")
    assertThat(keys(report["during"]["api"])).containsOnly("rate_rps", "p50", "p99", "error_share")
    assertThat(report["during"]["client_mean"].asDouble()).isEqualTo(0.075)
    assertThat(report["explanation"].asText().contains("R = 20 / 267 = 75 ms")).isTrue()
    assertThat(store().load().active!!.mode).isEqualTo("bench")

    assertThat(post(base, "/api/bench/report").status).isEqualTo(200)
    val state = store().load()
    assertThat(state.active).isNull()
    assertThat(state.history.single().mode).isEqualTo("bench")
    assertThat(get(base, "/api/bench/report").status).isEqualTo(404)
  }

  @Test
  fun `a quiz gives questions without answers and grades one answer at a time`() {
    val base = server()

    assertThat(post(base, "/api/quiz", mapOf("count" to 0)).json["error"].asText()).isEqualTo("bad_count")
    assertThat(post(base, "/api/quiz", mapOf("count" to QuizSessions.MAX_COUNT + 1)).status).isEqualTo(400)
    assertThat(post(base, "/api/quiz", mapOf("kind" to "trivia")).json["error"].asText()).isEqualTo("bad_kind")
    assertThat(post(base, "/api/quiz").json["questions"].size()).isEqualTo(QuizSessions.DEFAULT_COUNT)
    val quiz = post(base, "/api/quiz", mapOf("count" to 4, "kind" to "concepts"))
    assertThat(quiz.status).isEqualTo(201)
    val session = quiz.json["session"].asText()
    val first = quiz.json["questions"][0]
    assertThat(keys(first)).containsOnly("index", "kind", "text", "options")

    val graded = post(base, "/api/quiz/$session/0", mapOf("answer" to first["options"][0].asText())).json
    assertThat(keys(graded)).containsOnly("correct", "skipped", "answer", "explanation", "guide")
    assertThat(graded["skipped"].asBoolean()).isFalse()
    assertThat(graded["answer"].asText() in first["options"].map { it.asText() }).isTrue()
    assertThat(post(base, "/api/quiz/$session/0", mapOf("answer" to listOf(1))).json["error"].asText()).isEqualTo("bad_answer")
    assertThat(post(base, "/api/quiz/$session/1", mapOf("skip" to true, "answer" to "x")).json["error"].asText()).isEqualTo("bad_answer")
    assertThat(post(base, "/api/quiz/$session/1", mapOf("skip" to "yes")).json["error"].asText()).isEqualTo("bad_answer")
    val skipped = post(base, "/api/quiz/$session/1", mapOf("skip" to true)).json
    assertThat(skipped["skipped"].asBoolean()).isTrue()
    assertThat(skipped["correct"].asBoolean()).isFalse()
    assertThat(skipped["answer"].asText() in quiz.json["questions"][1]["options"].map { it.asText() }).isTrue()
    assertThat(post(base, "/api/quiz/$session/9", mapOf("answer" to "x")).json["error"].asText()).isEqualTo("no_question")
    assertThat(post(base, "/api/quiz/nope/0", mapOf("answer" to "x")).json["error"].asText()).isEqualTo("no_session")

    val numbers = post(base, "/api/quiz", mapOf("count" to 1, "kind" to "numbers")).json
    assertThat(numbers["questions"][0]["kind"].asText()).isEqualTo("number")
    assertThat(numbers["questions"][0]["unit"].isTextual).isTrue()
    assertThat(post(base, "/api/quiz/${numbers["session"].asText()}/0", mapOf("answer" to 12.5)).status).isEqualTo(200)
  }

  private fun pinPoolOfThree() {
    store().update { state -> state.copy(active = state.active!!.copy(params = mapOf("setup" to RampCatalog.POOL, "pool_max" to 3, "cpus" to 2.0, "every" to EVERY))) }
  }

  private fun choices(nature: String, location: String, signal: String, cause: String): Map<String, Any> =
    mapOf("nature" to nature, "location" to location, "signal" to signal, "cause" to cause)

  private fun rampAnswer(knee: Int, max: Int, resource: String): Map<String, Any> = mapOf("knee_rps" to knee, "max_rps" to max, "resource" to resource)

  private fun picks(options: AnswerOptions, nature: String, location: String, signal: String, cause: String): List<String> =
    listOf(index(options.natures, nature), index(options.locations, location), index(options.signals, signal), index(options.causes, cause))

  private fun index(options: List<Option>, key: String): String = (options.indexOfFirst { it.key == key }.also { check(it >= 0) { "$key is not offered" } } + 1).toString()

  private companion object {
    const val START = 1_700_000_000.0
    const val BENCH_START = START + 10_000
    const val EVERY = 60
    const val SCORED_SCENARIOS = 12
    const val SEED = 1
    val ZONE: ZoneId = ZoneId.of("UTC")
    const val PICKS_PER_SCENARIO = 3
    val WEB = mapOf("Content-Type" to "application/json", "X-Lab-Client" to "web")
    val WEB_HEADERS = arrayOf("Content-Type", "application/json", "X-Lab-Client", "web")
    val ACTIVE_VIEW_KEYS = arrayOf(
      "n", "key", "mode", "level", "ticket", "started_at", "elapsed_seconds", "hints_used", "hints_total", "hint_penalty", "hints", "answered", "rules", "options", "baseline_from"
    )
  }
}

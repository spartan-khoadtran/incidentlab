package com.c0x12c.incidentlab.labctl.api

import com.c0x12c.incidentlab.labctl.bench.BenchCatalog
import com.c0x12c.incidentlab.labctl.bench.BenchEngine
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.Format
import com.c0x12c.incidentlab.labctl.exercise.NewOptions
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.exercise.Refusal
import com.c0x12c.incidentlab.labctl.quiz.QuizAnswer
import com.c0x12c.incidentlab.labctl.quiz.QuizKind
import com.c0x12c.incidentlab.labctl.quiz.QuizSessions
import com.c0x12c.incidentlab.labctl.ramp.RampAnswer
import com.c0x12c.incidentlab.labctl.ramp.RampEngine
import com.c0x12c.incidentlab.labctl.ramp.RampPlan
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.state.Choices
import java.net.InetAddress
import java.net.InetSocketAddress

data class NewRequest(
  val mode: String? = null,
  val level: Int? = null,
  val topic: String? = null,
  val id: String? = null,
  val force: Boolean? = null
)

data class AnswerRequest(
  val nature: String? = null,
  val location: String? = null,
  val signal: String? = null,
  val cause: String? = null,
  val note: String? = null,
  val key: String? = null
)

/** The key of the exercise the caller shows. With it, a call for an exercise that is gone or replaced is refused. */
data class KeyRequest(val key: String? = null)

data class LoadRequest(val multiplier: Double? = null)

data class RampRequest(val every: Int? = null)

data class RampAnswerRequest(
  val kneeRps: Double? = null,
  val maxRps: Double? = null,
  val resource: String? = null,
  val note: String? = null,
  val key: String? = null
)

data class BenchRequest(
  val clients: Int? = null,
  val seconds: Int? = null
)

data class QuizRequest(
  val count: Int? = null,
  val kind: String? = null
)

/**
 * The lab API the handbook site calls through its nginx at /api/. Each handler checks its input,
 * calls an engine and returns what the engine returned; the engines hold every decision.
 */
class LabApi(
  private val exercises: ExerciseEngine,
  private val ramp: RampEngine,
  private val bench: BenchEngine,
  private val quiz: QuizSessions
) {

  val routes: List<Route> = listOf(
    get("/api/health") { ok(mapOf("ok" to true)) },
    get("/api/status") { ok(exercises.status()) },
    get("/api/options") { ok(exercises.options()) },
    post("/api/exercises") { created(exercises.start(newOptions(it.body(NewRequest::class))).orFail()) },
    get("/api/exercise") { ok(exercises.active().orFail()) },
    post("/api/exercise/hint") {
      ok(exercises.hint().orFail { refusal -> (refusal as? Refusal.WrongMode)?.let { noHints(it.mode) } })
    },
    post("/api/exercise/answer") { call ->
      val request = call.body(AnswerRequest::class)
      val choices = Choices(
        nature = required(request.nature, "nature"),
        location = required(request.location, "location"),
        signal = required(request.signal, "signal"),
        cause = required(request.cause, "cause")
      )
      ok(exercises.submit(choices, note(request.note), request.key).orFail())
    },
    post("/api/exercise/reveal") { ok(exercises.reveal(key(it)).orFail().reveal) },
    post("/api/exercise/clear") {
      exercises.clear(key(it)).orFail()
      ok(mapOf("cleared" to true))
    },
    get("/api/history") { ok(exercises.history()) },
    post("/api/load") { call ->
      val multiplier = call.body(LoadRequest::class).multiplier?.takeIf { it in ExerciseEngine.MIN_LOAD..ExerciseEngine.MAX_LOAD }
        ?: throw badRequest("bad_multiplier", "multiplier must be a number from ${Format.plain(ExerciseEngine.MIN_LOAD)} to ${Format.plain(ExerciseEngine.MAX_LOAD)}. 1 is the normal load.")
      ok(exercises.load(multiplier).orFail())
    },
    get("/api/ramp/options") { ok(mapOf("resources" to RampEngine.RESOURCE_OPTIONS)) },
    post("/api/ramp") { call ->
      val every = within(call.body(RampRequest::class).every ?: RampPlan.DEFAULT_STEP_SECONDS, "every", RampPlan.MIN_STEP_SECONDS, RampPlan.MAX_STEP_SECONDS)
      created(ramp.start(every).orFail())
    },
    post("/api/ramp/answer") { call ->
      val request = call.body(RampAnswerRequest::class)
      val answer = RampAnswer(
        kneeRps = rate(request.kneeRps, "knee_rps"),
        maxRps = rate(request.maxRps, "max_rps"),
        resource = required(request.resource, "resource")
      )
      ok(ramp.answer(answer, note(request.note), request.key).orFail())
    },
    post("/api/ramp/reveal") { ok(ramp.reveal(key(it)).orFail().result) },
    post("/api/bench") { call ->
      val request = call.body(BenchRequest::class)
      val clients = within(request.clients ?: BenchCatalog.DEFAULT_CLIENTS, "clients", BenchCatalog.MIN_CLIENTS, BenchCatalog.MAX_CLIENTS)
      val seconds = within(request.seconds ?: BenchCatalog.DEFAULT_SECONDS, "seconds", BenchCatalog.MIN_SECONDS, BenchCatalog.MAX_SECONDS)
      created(bench.start(clients, seconds).orFail())
    },
    get("/api/bench/report") { ok(bench.report(record = false).orFail()) },
    post("/api/bench/report") { ok(bench.report(record = true, key = key(it)).orFail()) },
    post("/api/quiz") { call ->
      val request = call.body(QuizRequest::class)
      val count = within(request.count ?: QuizSessions.DEFAULT_COUNT, "count", 1, QuizSessions.MAX_COUNT)
      val kind = QuizKind.entries.firstOrNull { it.flag == (request.kind ?: QuizKind.ALL.flag) }
        ?: throw badRequest("bad_kind", "kind must be one of ${QuizKind.entries.joinToString(", ") { it.flag }}.")
      created(quiz.open(count, kind))
    },
    post("/api/quiz/([^/]+)/([0-9]{1,6})") { call ->
      val node = call.tree().path("answer")
      val answer = when {
        node.isNumber -> QuizAnswer.Number(node.asDouble())
        node.isTextual -> QuizAnswer.Text(node.asText())
        else -> throw badRequest("bad_answer", "Send the answer as a number, or as the text of one option.")
      }
      ok(quiz.grade(call.params[0], call.params[1].toInt(), answer).orFail())
    }
  )

  fun start(port: Int, loopbackOnly: Boolean = true): ApiServer =
    ApiServer(routes, if (loopbackOnly) InetSocketAddress(InetAddress.getLoopbackAddress(), port) else InetSocketAddress(port))

  private fun newOptions(request: NewRequest): NewOptions {
    val mode = request.mode ?: ExerciseEngine.INCIDENT
    if (mode !in ExerciseEngine.NEW_MODES) throw badRequest("bad_mode", "mode must be one of ${ExerciseEngine.NEW_MODES.joinToString(", ")}.")
    val levels = exercises.levels()
    val level = request.level?.also { if (it !in levels) throw badRequest("bad_level", "level must be one of ${levels.joinToString(", ")}.") } ?: 0
    val topic = request.topic?.also { if (it !in Answers.TOPICS) throw badRequest("bad_topic", "No topic '$it'. /api/options lists the topics.") }.orEmpty()
    return NewOptions(level = level, topic = topic, id = request.id.orEmpty(), force = request.force == true, mode = mode)
  }

  private fun <T> Outcome<T>.orFail(special: (Refusal) -> ApiError? = { null }): T =
    when (this) {
      is Outcome.Done -> value
      is Outcome.Refused -> throw special(refusal) ?: apiError(refusal)
    }

  private fun apiError(refusal: Refusal): ApiError =
    when (refusal) {
      Refusal.ExerciseOpen -> conflict("exercise_open", "An exercise is still open. Answer, reveal or clear it first, or start the new one with force.")
      Refusal.NoExercise -> ApiError(NOT_FOUND, "no_exercise", "No exercise is running. Start one first.")
      is Refusal.Stale -> conflict("stale_exercise", "Exercise #${refusal.n} was started by an older version of the lab. Clear it, then start a new one.")
      is Refusal.WrongMode -> conflict("wrong_mode", "The open exercise is a ${refusal.mode}. Use the ${refusal.mode} page, or clear it.")
      Refusal.AlreadyAnswered -> conflict("already_answered", "This exercise was already submitted. Clear it to start the next one.")
      Refusal.ExerciseChanged -> conflict("exercise_changed", "The exercise on this page is no longer the open one: it was cleared, or another one started. Reload to see what is open now.")
      is Refusal.RampRunning -> conflict(
        "ramp_running",
        "A ramp is running and holds the load at 1 until its last step ends, in ${refusal.secondsLeft} s. Set the load after that, or clear the ramp.",
        mapOf("seconds_left" to refusal.secondsLeft)
      )
      Refusal.NoHintsLeft -> conflict("no_hints_left", "No hints left. Submit an answer, or reveal it.")
      is Refusal.UnknownId -> badRequest("unknown_id", "No exercise '${refusal.id}'. The catalog lists the ids.")
      is Refusal.NoCli -> badRequest("no_cli", "'${refusal.id}' has no CLI proof yet, so it cannot run in toolbox mode.")
      Refusal.NoMatch -> badRequest("no_match", "No exercise matches this level and topic.")
      is Refusal.Unsupported -> conflict(
        "unsupported",
        "${refusal.id?.let { "'$it' needs" } ?: "Every exercise that matches needs"} container settings the lab's controller cannot apply here (${refusal.missing.joinToString()}).",
        mapOf("missing" to refusal.missing.sorted())
      )
      is Refusal.UnknownKey -> badRequest("unknown_key", "'${refusal.key}' is not a ${refusal.question} key. The options endpoint lists them.")
      is Refusal.LoadNotOne -> conflict("load_not_one", "Background load is ${Format.plain(refusal.multiplier)} x. This exercise is tuned for the default load: set the load back to 1 first.")
      is Refusal.TooEarly -> conflict("too_early", "The ramp is not over yet: ${refusal.secondsLeft} s to go. Answer after the last step.", mapOf("seconds_left" to refusal.secondsLeft))
      Refusal.PrometheusDown -> ApiError(UNAVAILABLE, "prometheus_down", "Prometheus did not answer. Check that the lab is up, then try again.")
      is Refusal.StillRunning -> ApiError(TOO_EARLY, "still_running", "The bench is still running: ${refusal.secondsLeft} s left.", mapOf("seconds_left" to refusal.secondsLeft))
      Refusal.UnknownSession -> ApiError(NOT_FOUND, "no_session", "This quiz is over or has expired. Start a new one.")
      is Refusal.UnknownQuestion -> ApiError(NOT_FOUND, "no_question", "This quiz has no question ${refusal.index}.")
      is Refusal.BadAnswer -> badRequest("bad_answer", refusal.reason)
    }

  private fun noHints(mode: String) = conflict("no_hints_left", "A $mode has no hints.")

  private fun key(call: ApiCall): String? = call.body(KeyRequest::class).key

  private fun required(value: String?, field: String): String = value ?: throw badRequest("missing_field", "The field '$field' is needed.")

  private fun rate(value: Double?, field: String): Double =
    value?.takeIf { it >= 0 && it.isFinite() } ?: throw badRequest("missing_field", "The field '$field' is needed: a rate in rps, 0 or more.")

  private fun within(value: Int, field: String, min: Int, max: Int): Int =
    value.takeIf { it in min..max } ?: throw badRequest("bad_$field", "$field must be a whole number from $min to $max.")

  /** One line, as the CLI keeps it. */
  private fun note(raw: String?): String = raw.orEmpty().replace(LINE_BREAKS, " ").trim().take(NOTE_MAX)

  private fun conflict(code: String, message: String, extra: Map<String, Any> = emptyMap()) = ApiError(CONFLICT, code, message, extra)

  private fun badRequest(code: String, message: String) = ApiError(BAD_REQUEST, code, message)

  private fun ok(body: Any) = Reply(OK, body)

  private fun created(body: Any) = Reply(CREATED, body)

  private fun get(path: String, handle: (ApiCall) -> Reply) = Route("GET", Regex(path), handle)

  private fun post(path: String, handle: (ApiCall) -> Reply) = Route("POST", Regex(path), handle)

  private companion object {
    const val OK = 200
    const val CREATED = 201
    const val BAD_REQUEST = 400
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val TOO_EARLY = 425
    const val UNAVAILABLE = 503
    const val NOTE_MAX = 500
    val LINE_BREAKS = Regex("[\\r\\n]+")
  }
}

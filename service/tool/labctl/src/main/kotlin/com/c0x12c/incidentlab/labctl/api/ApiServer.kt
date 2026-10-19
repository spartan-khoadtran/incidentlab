package com.c0x12c.incidentlab.labctl.api

import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/** A refusal the client can act on: an HTTP status, a stable code and one plain sentence. */
class ApiError(
  val status: Int,
  val code: String,
  message: String,
  val extra: Map<String, Any> = emptyMap()
) : RuntimeException(message)

class Reply(
  val status: Int,
  val body: Any
)

/** One request as a handler sees it: the parts of the path its pattern captured, and the JSON body. */
class ApiCall(
  val params: List<String>,
  private val body: ByteArray
) {
  fun <T : Any> body(type: KClass<T>): T =
    LabJson.mapper.readValue(if (body.isEmpty()) EMPTY_OBJECT else body, type.java)
      ?: throw ApiError(ApiServer.HTTP_BAD_REQUEST, "bad_json", "The body must be a JSON object.")

  fun tree(): JsonNode = if (body.isEmpty()) LabJson.mapper.createObjectNode() else LabJson.mapper.readTree(body)

  private companion object {
    val EMPTY_OBJECT = "{}".toByteArray()
  }
}

class Route(
  val method: String,
  val pattern: Regex,
  val handle: (ApiCall) -> Reply
)

/**
 * A small JSON server on the JDK's HttpServer. Every POST needs `Content-Type: application/json`
 * and `X-Lab-Client: web`, which a cross-origin form or image cannot send without a preflight, and
 * no CORS header is ever sent, so the preflight fails. That keeps other origins from driving the lab.
 * A request whose Host is not this machine or `control` is refused, so a rebound DNS name cannot reach it.
 */
class ApiServer(
  private val routes: List<Route>,
  address: InetSocketAddress,
  threads: Int = THREADS
) : AutoCloseable, Logging {

  private val executor: ExecutorService = Executors.newFixedThreadPool(threads, daemonThreads())
  private val server: HttpServer = HttpServer.create(address, 0).apply {
    createContext("/") { exchange -> exchange.use(::handle) }
    executor = this@ApiServer.executor
    start()
  }

  val port: Int get() = server.address.port

  override fun close() {
    server.stop(0)
    executor.shutdownNow()
  }

  private fun handle(exchange: HttpExchange) {
    val started = System.nanoTime()
    val reply = try {
      dispatch(exchange)
    } catch (e: ApiError) {
      error(e)
    } catch (e: JsonProcessingException) {
      error(badJson(e))
    } catch (e: Exception) {
      warn(e) { "api ${exchange.requestMethod} ${exchange.requestURI.path} failed" }
      error(ApiError(HTTP_INTERNAL_ERROR, "internal", "The lab controller hit an error. Its log has the details."))
    }
    val bytes = WRITER.writeValueAsBytes(reply.body)
    exchange.responseHeaders.apply {
      set("Content-Type", "application/json; charset=utf-8")
      set("Cache-Control", "no-store")
      set("X-Content-Type-Options", "nosniff")
    }
    exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
    exchange.responseBody.write(bytes)
    info { "api ${exchange.requestMethod} ${exchange.requestURI.path} -> ${reply.status} in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms" }
  }

  private fun dispatch(exchange: HttpExchange): Reply {
    val method = exchange.requestMethod
    if (hostName(exchange.requestHeaders.getFirst("Host")) !in ALLOWED_HOSTS) {
      throw ApiError(HTTP_FORBIDDEN, "forbidden_host", "The lab API answers only on localhost.")
    }
    if (method == "OPTIONS") throw forbidden()
    val path = exchange.requestURI.path
    val matching = routes.mapNotNull { route -> route.pattern.matchEntire(path)?.let { route to it.groupValues.drop(1) } }
    if (matching.isEmpty()) throw ApiError(HTTP_NOT_FOUND, "not_found", "There is no $path in the lab API.")
    val (route, params) = matching.firstOrNull { it.first.method == method }
      ?: throw ApiError(HTTP_BAD_METHOD, "method_not_allowed", "$path does not take $method.")
    if (method == "POST") {
      val type = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
      if (type != JSON || exchange.requestHeaders.getFirst(CLIENT_HEADER) != CLIENT) throw forbidden()
    }
    return route.handle(ApiCall(params, body(exchange)))
  }

  private fun body(exchange: HttpExchange): ByteArray {
    val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0
    if (declared > MAX_BODY_BYTES) throw tooLarge()
    val bytes = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
    if (bytes.size > MAX_BODY_BYTES) throw tooLarge()
    return bytes
  }

  private fun badJson(e: JsonProcessingException): ApiError {
    val field = (e as? JsonMappingException)?.path?.mapNotNull { it.fieldName }?.joinToString(".").orEmpty()
    val message = if (field.isEmpty()) "The body is not valid JSON." else "The field '$field' has the wrong type."
    return ApiError(HTTP_BAD_REQUEST, "bad_json", message)
  }

  private fun error(e: ApiError): Reply = Reply(e.status, mapOf("error" to e.code, "message" to e.message.orEmpty()) + e.extra)

  private fun forbidden() = ApiError(HTTP_FORBIDDEN, "forbidden", "Write calls need the headers Content-Type: application/json and $CLIENT_HEADER: $CLIENT.")

  private fun tooLarge() = ApiError(HTTP_TOO_LARGE, "too_large", "The body is larger than ${MAX_BODY_BYTES / KIB} KB.")

  private fun hostName(header: String?): String {
    val host = header?.trim()?.lowercase().orEmpty()
    return if (host.startsWith("[")) host.substringBefore("]") + "]" else host.substringBefore(":")
  }

  private fun daemonThreads(): ThreadFactory {
    val count = AtomicInteger()
    return ThreadFactory { task -> Thread(task, "lab-api-${count.incrementAndGet()}").apply { isDaemon = true } }
  }

  companion object {
    const val DEFAULT_PORT = 8090
    const val THREADS = 4
    const val MAX_BODY_BYTES = 64 * 1024
    const val CLIENT_HEADER = "X-Lab-Client"
    const val CLIENT = "web"
    val ALLOWED_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "control")
    private const val JSON = "application/json"
    private const val KIB = 1024
    private const val NANOS_PER_MILLI = 1_000_000
    internal const val HTTP_BAD_REQUEST = 400
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_BAD_METHOD = 405
    private const val HTTP_TOO_LARGE = 413
    private const val HTTP_INTERNAL_ERROR = 500
    private val WRITER = LabJson.mapper.writer().without(SerializationFeature.INDENT_OUTPUT)
  }
}

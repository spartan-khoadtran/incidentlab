package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import com.fasterxml.jackson.databind.JsonNode
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong

/** Runs a command inside a compose service's container and returns what it printed. Throws on a non-zero exit. */
fun interface ContainerExec {
  fun exec(service: String, command: List<String>): String
}

/**
 * What the controller does through the Docker socket: resize the api container's CPU limit for
 * the throttling exercise, and run the netem script inside it for the network exercises. Raw
 * engine API calls over the unix socket; the containers are found by the labels compose puts on them.
 */
class DockerControl(
  private val project: String,
  socket: Path
) : ContainerExec, Logging {

  private val client: DockerHttpClient = ZerodepDockerHttpClient.Builder().dockerHost(URI.create("unix://$socket")).build()

  fun ensureCpus(service: String, cpus: Double) {
    val id = containerId(service) ?: return
    val wanted = (cpus * NANOS_PER_CPU).roundToLong()
    val current = call(DockerHttpClient.Request.Method.GET, "/containers/$id/json").path("HostConfig").path("NanoCpus").asLong()
    if (current == wanted) {
      return
    }
    val response = call(DockerHttpClient.Request.Method.POST, "/containers/$id/update", mapOf("NanoCpus" to wanted))
    val warnings = response.path("Warnings")
    if (!warnings.isMissingNode && warnings.size() > 0) {
      warn { "docker update $service: $warnings" }
    }
  }

  override fun exec(service: String, command: List<String>): String {
    val id = containerId(service) ?: throw IllegalStateException("docker exec: no running container for $service")
    val created = call(
      DockerHttpClient.Request.Method.POST,
      "/containers/$id/exec",
      mapOf("AttachStdout" to true, "AttachStderr" to true, "Tty" to false, "Cmd" to command)
    )
    val execId = created.path("Id").asText()
    val output = demux(bytes(DockerHttpClient.Request.Method.POST, "/exec/$execId/start", mapOf("Detach" to false, "Tty" to false)))
    val exit = exitCode(execId)
    if (exit != 0) {
      throw IllegalStateException("docker exec ${command.joinToString(" ")} in $service -> exit $exit: ${output.trim()}")
    }
    return output
  }

  /** The stream closes when the process ends, but the inspect record can lag behind it by a moment. */
  private fun exitCode(execId: String): Int {
    repeat(EXIT_POLLS) {
      val inspect = call(DockerHttpClient.Request.Method.GET, "/exec/$execId/json")
      if (!inspect.path("Running").asBoolean(false)) {
        return inspect.path("ExitCode").asInt()
      }
      Thread.sleep(EXIT_POLL_MILLIS)
    }
    throw IllegalStateException("docker exec $execId still running")
  }

  private fun containerId(service: String): String? {
    val filters = LabJson.mapper.writeValueAsString(
      mapOf("label" to listOf("com.docker.compose.project=$project", "com.docker.compose.service=$service"))
    )
    val containers = call(DockerHttpClient.Request.Method.GET, "/containers/json?filters=" + URLEncoder.encode(filters, Charsets.UTF_8))
    return containers.firstOrNull()?.path("Id")?.asText()
  }

  private fun call(method: DockerHttpClient.Request.Method, path: String, body: Any? = null): JsonNode {
    val text = bytes(method, path, body).toString(Charsets.UTF_8)
    return if (text.isBlank()) LabJson.mapper.nullNode() else LabJson.mapper.readTree(text)
  }

  private fun bytes(method: DockerHttpClient.Request.Method, path: String, body: Any? = null): ByteArray =
    DockerHttpClient.Request.builder()
      .method(method)
      .path(path)
      .apply {
        if (body != null) {
          putHeader("Content-Type", "application/json")
          bodyBytes(*LabJson.mapper.writeValueAsBytes(body))
        }
      }
      .build()
      .let { request ->
        client.execute(request).use { response ->
          val content = response.body.readAllBytes()
          if (response.statusCode >= HTTP_ERROR) {
            throw IllegalStateException("docker $method $path -> ${response.statusCode} ${content.toString(Charsets.UTF_8)}")
          }
          content
        }
      }

  companion object {
    private const val NANOS_PER_CPU = 1_000_000_000.0
    private const val HTTP_ERROR = 300
    private const val EXIT_POLLS = 40
    private const val EXIT_POLL_MILLIS = 50L
    private const val FRAME_HEADER = 8
    private const val FRAME_TYPE_INDEX = 0
    private const val FRAME_SIZE_INDEX = 4
    private const val STDERR = 2

    /** Null when the socket is not mounted, which only disables the CPU-limit and network exercises. */
    fun ifAvailable(project: String, socket: Path): DockerControl? =
      if (Files.exists(socket)) DockerControl(project, socket) else null

    /**
     * Without a TTY the engine multiplexes stdout and stderr into frames: one byte of stream type
     * (0 stdin, 1 stdout, 2 stderr), three zero bytes, then a big-endian uint32 payload size.
     * Both streams come back in order as one text. Bytes that do not parse as frames are returned as they are.
     */
    internal fun demux(stream: ByteArray): String {
      val out = ByteArrayOutputStream()
      var offset = 0
      while (offset + FRAME_HEADER <= stream.size) {
        val type = stream[offset + FRAME_TYPE_INDEX].toInt()
        val size = ByteBuffer.wrap(stream, offset + FRAME_SIZE_INDEX, Int.SIZE_BYTES).int
        val zeros = (1 until FRAME_SIZE_INDEX).all { stream[offset + it] == 0.toByte() }
        if (type !in 0..STDERR || !zeros || size < 0 || offset + FRAME_HEADER + size > stream.size) {
          return stream.toString(Charsets.UTF_8)
        }
        out.write(stream, offset + FRAME_HEADER, size)
        offset += FRAME_HEADER + size
      }
      return if (offset == stream.size) out.toString(Charsets.UTF_8) else stream.toString(Charsets.UTF_8)
    }
  }
}

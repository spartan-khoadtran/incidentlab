package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.labctl.LabJson
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong

/**
 * The one thing the controller does through the Docker socket: resize the api container's CPU
 * limit for the throttling exercise. Raw engine API calls over the unix socket; the containers are
 * found by the labels compose puts on them.
 */
class DockerControl(
  private val project: String,
  socket: Path
) : Logging {

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

  private fun containerId(service: String): String? {
    val filters = LabJson.mapper.writeValueAsString(
      mapOf("label" to listOf("com.docker.compose.project=$project", "com.docker.compose.service=$service"))
    )
    val containers = call(DockerHttpClient.Request.Method.GET, "/containers/json?filters=" + URLEncoder.encode(filters, Charsets.UTF_8))
    return containers.firstOrNull()?.path("Id")?.asText()
  }

  private fun call(method: DockerHttpClient.Request.Method, path: String, body: Any? = null) =
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
          val text = response.body.readAllBytes().toString(Charsets.UTF_8)
          if (response.statusCode >= HTTP_ERROR) {
            throw IllegalStateException("docker $method $path -> ${response.statusCode} $text")
          }
          if (text.isBlank()) LabJson.mapper.nullNode() else LabJson.mapper.readTree(text)
        }
      }

  companion object {
    private const val NANOS_PER_CPU = 1_000_000_000.0
    private const val HTTP_ERROR = 300

    /** Null when the socket is not mounted, which only disables the CPU-limit exercise. */
    fun ifAvailable(project: String, socket: Path): DockerControl? =
      if (Files.exists(socket)) DockerControl(project, socket) else null
  }
}

package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
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
import java.time.Duration
import kotlin.math.roundToLong

/** Runs a command inside a compose service's container and returns what it printed. Throws on a non-zero exit. */
fun interface ContainerExec {
  fun exec(service: String, command: List<String>): String
}

/** One raw call to the Docker engine API. Throws when the engine answers with an error status. */
fun interface DockerEngine {
  fun call(method: DockerHttpClient.Request.Method, path: String, body: Any?): ByteArray
}

/** Without them a stalled engine blocks a call for ever, and with it the daemon's start. */
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(15)

/** The engine answered with an error status, so it is up: unlike a timeout, a retry will not help. */
class DockerStatusException(val status: Int, message: String) : IllegalStateException(message)

/** The engine API over the unix socket, through `docker-java-transport-zerodep`. */
class SocketEngine(socket: Path) : DockerEngine {

  private val client: DockerHttpClient = ZerodepDockerHttpClient.Builder()
    .dockerHost(URI.create("unix://$socket"))
    .connectionTimeout(CONNECT_TIMEOUT)
    .responseTimeout(RESPONSE_TIMEOUT)
    .build()

  override fun call(method: DockerHttpClient.Request.Method, path: String, body: Any?): ByteArray =
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
            throw DockerStatusException(response.statusCode, "docker $method $path -> ${response.statusCode} ${content.toString(Charsets.UTF_8)}")
          }
          content
        }
      }

  private companion object {
    const val HTTP_ERROR = 300
  }
}

/**
 * What the controller does through the Docker socket: resize the api container's CPU limit for
 * the throttling exercise, run the netem script inside it for the network exercises, and find a
 * container's id for the cgroup applier. A service's container is found by the labels compose puts
 * on it, in the compose project the controller itself runs in, so `docker compose -p` works.
 */
class DockerControl(
  private val engine: DockerEngine,
  val project: String
) : ContainerExec, Logging {

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
    val id = containerId(service) ?: throw IllegalStateException("docker exec: no container for $service in compose project $project")
    val created = call(
      DockerHttpClient.Request.Method.POST,
      "/containers/$id/exec",
      mapOf("AttachStdout" to true, "AttachStderr" to true, "Tty" to false, "Cmd" to command)
    )
    val execId = created.path("Id").asText()
    val output = demux(engine.call(DockerHttpClient.Request.Method.POST, "/exec/$execId/start", mapOf("Detach" to false, "Tty" to false)))
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

  /** A stopped container still counts, so a crash loop does not look like a missing service. A running one wins. */
  fun containerId(service: String): String? {
    val filters = LabJson.mapper.writeValueAsString(
      mapOf("label" to listOf("$PROJECT_LABEL=$project", "$SERVICE_LABEL=$service", "$ONEOFF_LABEL=False"))
    )
    val containers = call(DockerHttpClient.Request.Method.GET, "/containers/json?all=1&filters=" + URLEncoder.encode(filters, Charsets.UTF_8))
    return containers.sortedByDescending { it.path("State").asText() == "running" }.firstOrNull()?.path("Id")?.asText()
  }

  /**
   * The container appliers whose containers this project has. One whose container is missing is left
   * out, so `capabilities.json` does not list its keys and no exercise that needs it can start. A
   * lookup the engine did not answer throws, so the caller can try again later.
   */
  fun containerAppliers(cgroups: ((String) -> String?) -> Applier?): List<Applier> {
    val missing = Services.BASE_DOCKER.keys.filter { containerId(it) == null }.toSet()
    if (missing.isNotEmpty()) {
      info { "compose project $project has no container for ${missing.sorted().joinToString()}: the exercises that change it are disabled" }
    }
    fun found(containers: Set<String>) = containers.none { it in missing }
    return listOfNotNull(
      ContainerCpuApplier(this).takeIf { found(containersWith(ContainerCpuApplier.CPUS)) },
      NetemApplier(this).takeIf { found(Services.NETEM_CONTAINERS) },
      if (found(containersWith(CgroupApplier.WRITE_IOPS))) cgroups(::containerId) else null
    )
  }

  private fun containersWith(key: String): Set<String> = Services.BASE_DOCKER.filterValues { key in it }.keys

  private fun call(method: DockerHttpClient.Request.Method, path: String, body: Any? = null): JsonNode = read(engine, method, path, body)

  companion object : Logging {
    private const val NANOS_PER_CPU = 1_000_000_000.0
    private const val EXIT_POLLS = 40
    private const val EXIT_POLL_MILLIS = 50L
    private const val FRAME_HEADER = 8
    private const val FRAME_TYPE_INDEX = 0
    private const val FRAME_SIZE_INDEX = 4
    private const val STDERR = 2
    private const val HTTP_NOT_FOUND = 404
    private val FIRST_RETRY: Duration = Duration.ofSeconds(5)
    private val LAST_RETRY: Duration = Duration.ofSeconds(60)
    const val PROJECT_LABEL = "com.docker.compose.project"
    const val SERVICE_LABEL = "com.docker.compose.service"
    const val ONEOFF_LABEL = "com.docker.compose.oneoff"
    private val CONTAINER_DIR = Regex("/containers/([0-9a-f]{64})/")

    /** Null, with one log line, when the socket is not mounted or the compose project is unknown. Throws when the engine does not answer. */
    fun ifAvailable(
      socket: Path,
      projectOverride: String?,
      hostname: String? = System.getenv("HOSTNAME"),
      mountinfo: Path = Path.of("/proc/self/mountinfo")
    ): DockerControl? {
      if (!Files.exists(socket)) {
        info { "docker socket not found: CPU-limit, network and disk IOPS exercises are disabled" }
        return null
      }
      val engine = SocketEngine(socket)
      val project = projectOverride?.takeIf { it.isNotBlank() } ?: projectOf(engine, ownIds(hostname, mountinfo))
      if (project == null) {
        info { "cannot find the compose project of this container: CPU-limit, network and disk IOPS exercises are disabled" }
        return null
      }
      info { "docker: compose project $project" }
      return DockerControl(engine, project)
    }

    /** A candidate the engine does not know (404) is skipped; any other failure means the engine did not answer, so it is thrown. */
    internal fun projectOf(engine: DockerEngine, candidates: List<String>): String? =
      candidates.asSequence()
        .mapNotNull { id ->
          try {
            read(engine, DockerHttpClient.Request.Method.GET, "/containers/$id/json")
          } catch (e: DockerStatusException) {
            if (e.status == HTTP_NOT_FOUND) null else throw e
          }
        }
        .mapNotNull { it.path("Config").path("Labels").path(PROJECT_LABEL).textValue()?.takeIf(String::isNotBlank) }
        .firstOrNull()

    /**
     * Finds the engine and the containers, trying again with a growing wait while the engine does not
     * answer, then hands the container appliers to [ready]. A missing socket, project or container is
     * final: it is logged once and those exercises stay off.
     */
    fun discover(
      open: () -> DockerControl?,
      cgroups: ((String) -> String?) -> Applier?,
      ready: (List<Applier>) -> Unit,
      sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }
    ) {
      var wait = FIRST_RETRY
      while (true) {
        try {
          val docker = open() ?: return
          ready(docker.containerAppliers(cgroups))
          return
        } catch (e: Exception) {
          warn { "docker: the engine did not answer (${e.message}); CPU-limit, network and disk IOPS exercises wait, trying again in ${wait.seconds} s" }
          sleep(wait)
          wait = minOf(wait.multipliedBy(2), LAST_RETRY)
        }
      }
    }

    /** Docker bind mounts `/etc/hostname` from the container's own directory, so mountinfo holds the full id even with a custom `hostname:`. */
    internal fun ownIds(hostname: String?, mountinfo: Path): List<String> {
      val mounted = runCatching { CONTAINER_DIR.findAll(Files.readString(mountinfo)).map { it.groupValues[1] }.toList() }.getOrDefault(emptyList())
      return (mounted + listOfNotNull(hostname?.takeIf { it.isNotBlank() })).distinct()
    }

    private fun read(engine: DockerEngine, method: DockerHttpClient.Request.Method, path: String, body: Any? = null): JsonNode {
      val text = engine.call(method, path, body).toString(Charsets.UTF_8)
      return if (text.isBlank()) LabJson.mapper.nullNode() else LabJson.mapper.readTree(text)
    }

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

package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import com.c0x12c.incidentlab.labctl.LabJson
import com.github.dockerjava.transport.DockerHttpClient
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DockerControlTest {

  private val control = container(1, "foo", "control")

  @Test
  fun `the project comes from the controller's own labels`() {
    val engine = FakeEngine(control, container(2, "bar", "api"))

    assertThat(DockerControl.projectOf(engine, listOf("ffffffffffff", control.id.take(12)))).isEqualTo("foo")
  }

  @Test
  fun `no known candidate means no project`() {
    val engine = FakeEngine(control)

    assertThat(DockerControl.projectOf(engine, listOf("custom-hostname"))).isNull()
    assertThat(DockerControl.projectOf(engine, emptyList())).isNull()
  }

  @Test
  fun `the full id from mountinfo comes before the hostname`(@TempDir dir: Path) {
    val mountinfo = dir.resolve("mountinfo")
    Files.writeString(
      mountinfo,
      "1201 1190 254:1 /docker/containers/${control.id}/hostname /etc/hostname rw,relatime - ext4 /dev/vda1 rw\n" +
        "1202 1190 254:1 /docker/volumes /host/volumes ro,relatime - ext4 /dev/vda1 rw\n"
    )

    assertThat(DockerControl.ownIds("9c144b36bf22", mountinfo)).containsExactly(control.id, "9c144b36bf22")
    assertThat(DockerControl.ownIds("9c144b36bf22", dir.resolve("missing"))).containsExactly("9c144b36bf22")
  }

  @Test
  fun `a service is found by its compose labels in this project only`() {
    val running = container(3, "foo", "api")
    val engine = FakeEngine(
      container(2, "bar", "api"),
      container(4, "foo", "api", running = false),
      running,
      container(5, "foo", "api", oneoff = true),
      container(6, "foo", "postgres", running = false)
    )
    val docker = DockerControl(engine, "foo")

    assertThat(docker.containerId("api")).isEqualTo(running.id)
    assertThat(docker.containerId("postgres")).isEqualTo(container(6, "foo", "postgres").id)
    assertThat(docker.containerId("pricing")).isNull()
  }

  @Test
  fun `a missing api leaves out the CPU and netem keys`() {
    val postgres = container(6, "foo", "postgres")
    var resolved: String? = null
    val appliers = DockerControl(FakeEngine(postgres), "foo").containerAppliers { containerId ->
      resolved = containerId("postgres")
      cgroupStub
    }

    assertThat(appliers.flatMap { it.dockerKeys }).containsExactly(CgroupApplier.WRITE_IOPS)
    assertThat(resolved).isEqualTo(postgres.id)
  }

  @Test
  fun `a project with no lab containers gets no container keys`() {
    var asked = false
    val appliers = DockerControl(FakeEngine(container(2, "bar", "api"), container(6, "bar", "postgres")), "foo").containerAppliers {
      asked = true
      cgroupStub
    }

    assertThat(appliers).isEmpty()
    assertThat(asked).isFalse()
  }

  @Test
  fun `every container found wires CPU, netem and the cgroup applier`() {
    val appliers = DockerControl(FakeEngine(container(3, "foo", "api"), container(6, "foo", "postgres")), "foo").containerAppliers { cgroupStub }

    assertThat(appliers.flatMap { it.dockerKeys }.sorted())
      .containsExactly(ContainerCpuApplier.CPUS, NetemApplier.NETEM, CgroupApplier.WRITE_IOPS)
  }

  @Test
  fun `stdout and stderr frames are joined in order`() {
    val stream = frames(1 to "netem: pricing=172.18.0.5 delay=0ms loss=2%;\n", 2 to "warning\n", 1 to "done\n")

    assertThat(DockerControl.demux(stream)).isEqualTo("netem: pricing=172.18.0.5 delay=0ms loss=2%;\nwarning\ndone\n")
  }

  @Test
  fun `bytes that are not frames come back as they are`() {
    val raw = "plain tty output\n".toByteArray()

    assertThat(DockerControl.demux(raw)).isEqualTo("plain tty output\n")
    assertThat(DockerControl.demux(ByteArray(0))).isEqualTo("")
  }

  @Test
  fun `a truncated frame falls back to the raw bytes`() {
    val whole = frames(1 to "abcdef")
    val cut = whole.copyOf(whole.size - 2)

    assertThat(DockerControl.demux(cut)).isEqualTo(cut.toString(Charsets.UTF_8))
  }

  private fun frames(vararg parts: Pair<Int, String>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((type, text) in parts) {
      val payload = text.toByteArray()
      out.write(ByteBuffer.allocate(8).put(type.toByte()).put(0).put(0).put(0).putInt(payload.size).array())
      out.write(payload)
    }
    return out.toByteArray()
  }

  private val cgroupStub = object : Applier {
    override val dockerKeys = setOf(CgroupApplier.WRITE_IOPS)

    override fun apply(desired: Desired) = Unit
  }

  private class FakeContainer(val id: String, val running: Boolean, val labels: Map<String, String>)

  private fun container(n: Int, project: String, service: String, running: Boolean = true, oneoff: Boolean = false) =
    FakeContainer(
      n.toString().padEnd(64, 'c'),
      running,
      mapOf(
        DockerControl.PROJECT_LABEL to project,
        DockerControl.SERVICE_LABEL to service,
        DockerControl.ONEOFF_LABEL to if (oneoff) "True" else "False"
      )
    )

  /** Answers the two engine calls the lookups make, the way dockerd does: a list filtered by labels, and an inspect by id prefix. */
  private class FakeEngine(vararg containers: FakeContainer) : DockerEngine {

    private val containers = containers.toList()

    override fun call(method: DockerHttpClient.Request.Method, path: String, body: Any?): ByteArray {
      val uri = URI.create(path)
      val answer: Any = when {
        uri.path == "/containers/json" -> list(query(uri))
        uri.path.startsWith("/containers/") && uri.path.endsWith("/json") -> inspect(uri.path.removePrefix("/containers/").removeSuffix("/json"))
        else -> throw IllegalStateException("unexpected docker $method $path")
      }
      return LabJson.mapper.writeValueAsBytes(answer)
    }

    private fun query(uri: URI): Map<String, String> =
      uri.rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }

    private fun list(query: Map<String, String>): List<Map<String, String>> {
      val all = query["all"] in setOf("1", "true")
      val labels = LabJson.mapper.readTree(query["filters"] ?: "{}").path("label").map { it.asText() }
      return containers
        .filter { all || it.running }
        .filter { c -> labels.all { c.labels[it.substringBefore("=")] == it.substringAfter("=") } }
        .map { mapOf("Id" to it.id, "State" to if (it.running) "running" else "exited") }
    }

    private fun inspect(id: String): Map<String, Any> {
      val found = containers.firstOrNull { it.id.startsWith(id) } ?: throw IllegalStateException("docker GET /containers/$id/json -> 404")
      return mapOf("Id" to found.id, "Config" to mapOf("Labels" to found.labels))
    }
  }
}

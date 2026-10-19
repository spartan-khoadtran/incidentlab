package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CgroupApplierTest {

  private val ids = mapOf("postgres" to "abc123")

  private fun desired(vararg docker: Pair<String, Map<String, Any?>>) = Desired(faults = emptyMap(), docker = docker.toMap())

  private fun ioMax(root: Path, dir: String, content: String): Path =
    Files.createDirectories(root.resolve(dir)).resolve("io.max").also { Files.writeString(it, content) }

  @Test
  fun `a write limit is written for the disk and left alone once it is there`(@TempDir root: Path) {
    val file = ioMax(root, "docker/abc123", "")
    val applier = CgroupApplier(ids::get, root, "254:16")

    applier.apply(desired("postgres" to mapOf("write_iops" to 60)))
    assertThat(Files.readString(file)).isEqualTo("254:16 wiops=60\n")

    Files.writeString(file, "254:16 rbps=max wbps=max riops=max wiops=60\n")
    applier.apply(desired("postgres" to mapOf("write_iops" to 60)))
    assertThat(Files.readString(file)).isEqualTo("254:16 rbps=max wbps=max riops=max wiops=60\n")
  }

  @Test
  fun `the base value removes a limit, and a disk with no line needs no write`(@TempDir root: Path) {
    val limited = ioMax(root, "docker/abc123", "254:16 rbps=max wbps=max riops=max wiops=60\n")
    val applier = CgroupApplier(ids::get, root, "254:16")

    applier.apply(desired("postgres" to Services.BASE_DOCKER.getValue("postgres")))
    assertThat(Files.readString(limited)).isEqualTo("254:16 wiops=max\n")

    Files.writeString(limited, "")
    applier.apply(desired("postgres" to mapOf("write_iops" to "max")))
    assertThat(Files.readString(limited)).isEqualTo("")
  }

  @Test
  fun `the systemd cgroup layout works too, and containers without the key are skipped`(@TempDir root: Path) {
    val file = ioMax(root, "system.slice/docker-abc123.scope", "")
    val applier = CgroupApplier(ids::get, root, "8:0")

    applier.apply(desired("api" to mapOf("cpus" to 2.0), "postgres" to mapOf("write_iops" to 45.0)))

    assertThat(Files.readString(file)).isEqualTo("8:0 wiops=45\n")
  }

  @Test
  fun `a missing container or cgroup is skipped without an exception`(@TempDir root: Path) {
    val applier = CgroupApplier(ids::get, root, "254:16")

    applier.apply(desired("postgres" to mapOf("write_iops" to 60), "redis" to mapOf("write_iops" to 60)))
  }

  @Test
  fun `io max lines are read per disk`() {
    val content = "8:0 rbps=max wbps=max riops=max wiops=120\n254:16 rbps=2097152 wbps=max riops=max wiops=max\n"

    assertThat(CgroupApplier.writeIops(content, "8:0")).isEqualTo("120")
    assertThat(CgroupApplier.writeIops(content, "254:16")).isEqualTo("max")
    assertThat(CgroupApplier.writeIops(content, "254:32")).isEqualTo("max")
    assertThat(CgroupApplier.limit(0)).isEqualTo("max")
    assertThat(CgroupApplier.limit(null)).isEqualTo("max")
    assertThat(CgroupApplier.limit(72.6)).isEqualTo("72")
  }

  @Test
  fun `the applier is only built when every host path is mounted`(@TempDir root: Path) {
    val cgroup = Files.createDirectories(root.resolve("cgroup"))
    val volumes = Files.createDirectories(root.resolve("volumes"))

    assertThat(CgroupApplier.ifAvailable(ids::get, cgroup, volumes, root.resolve("sys"))).isNull()
    assertThat(CgroupApplier.ifAvailable(ids::get, root.resolve("nope"), volumes, cgroup)).isNull()
  }

  @Test
  fun `the applier finds the whole disk under the volumes path`(@TempDir root: Path) {
    val volumes = Files.createDirectories(root.resolve("volumes"))
    val device = BlockDevice.majorMinor(Files.getAttribute(volumes, "unix:dev") as Long)
    val sys = root.resolve("sys")
    val disk = Files.createDirectories(sys.resolve("devices/virtual/block/vdb"))
    Files.writeString(disk.resolve("dev"), "254:16\n")
    val partition = Files.createDirectories(disk.resolve("vdb1"))
    Files.writeString(partition.resolve("partition"), "1\n")
    Files.writeString(partition.resolve("dev"), "254:17\n")
    Files.createDirectories(sys.resolve("dev/block"))
    Files.createSymbolicLink(sys.resolve("dev/block/254:17"), Path.of("../../devices/virtual/block/vdb/vdb1"))
    if (device != "254:17") {
      Files.createSymbolicLink(sys.resolve("dev/block/$device"), Path.of("../../devices/virtual/block/vdb/vdb1"))
    }
    Files.createDirectories(sys.resolve("class/block"))
    Files.createSymbolicLink(sys.resolve("class/block/vdb1"), Path.of("../../devices/virtual/block/vdb/vdb1"))
    val mountinfo = Files.writeString(root.resolve("mountinfo"), "2411 2399 0:36 /docker/volumes $volumes ro - btrfs /dev/vdb1 rw\n")

    val applier = CgroupApplier.ifAvailable(ids::get, Files.createDirectories(root.resolve("cgroup")), volumes, sys, mountinfo)

    assertThat(applier).isNotNull()
    val file = ioMax(root, "cgroup/docker/abc123", "")
    applier!!.apply(desired("postgres" to mapOf("write_iops" to 50)))
    assertThat(Files.readString(file)).isEqualTo("254:16 wiops=50\n")
  }
}

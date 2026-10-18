package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BlockDeviceTest {

  /** glibc's makedev(), the inverse of what [BlockDevice.majorMinor] undoes. */
  private fun makedev(major: Long, minor: Long): Long =
    ((major and 0xfffL) shl 8) or ((major and 0xfffff000L) shl 32) or (minor and 0xffL) or ((minor and 0xffffff00L) shl 12)

  @Test
  fun `st_dev splits into major and minor like glibc`() {
    assertThat(BlockDevice.majorMinor(65_041)).isEqualTo("254:17")
    assertThat(BlockDevice.majorMinor(makedev(8, 0))).isEqualTo("8:0")
    assertThat(BlockDevice.majorMinor(makedev(259, 3))).isEqualTo("259:3")
    assertThat(BlockDevice.majorMinor(makedev(4_100, 70_000))).isEqualTo("4100:70000")
  }

  @Test
  fun `a partition maps to its parent disk and a whole disk maps to itself`(@TempDir sys: Path) {
    val disk = Files.createDirectories(sys.resolve("devices/pci0000:00/virtio2/block/vdb"))
    Files.writeString(disk.resolve("dev"), "254:16\n")
    val partition = Files.createDirectories(disk.resolve("vdb1"))
    Files.writeString(partition.resolve("dev"), "254:17\n")
    Files.writeString(partition.resolve("partition"), "1\n")
    val links = Files.createDirectories(sys.resolve("dev/block"))
    Files.createSymbolicLink(links.resolve("254:17"), Path.of("../../devices/pci0000:00/virtio2/block/vdb/vdb1"))
    Files.createSymbolicLink(links.resolve("254:16"), Path.of("../../devices/pci0000:00/virtio2/block/vdb"))

    assertThat(BlockDevice.wholeDisk(sys, "254:17")).isEqualTo("254:16")
    assertThat(BlockDevice.wholeDisk(sys, "254:16")).isEqualTo("254:16")
  }
}

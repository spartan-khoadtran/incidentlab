package com.c0x12c.incidentlab.labctl.control

import java.nio.file.Files
import java.nio.file.Path

/** Finds the whole disk under a path, as the `MAJ:MIN` that cgroup v2 `io.max` takes. */
object BlockDevice {

  /** Splits a Linux `st_dev` the way glibc's `major()` and `minor()` do. */
  fun majorMinor(dev: Long): String {
    val major = ((dev ushr 8) and 0xfffL) or ((dev ushr 32) and 0xfffff000L)
    val minor = (dev and 0xffL) or ((dev ushr 12) and 0xffffff00L)
    return "$major:$minor"
  }

  /** A partition maps to its parent disk through sysfs; `io.max` only takes a whole disk. */
  fun wholeDisk(sys: Path, majorMinor: String): String {
    val device = sys.resolve("dev/block/$majorMinor").toRealPath()
    return if (Files.exists(device.resolve("partition"))) Files.readString(device.parent.resolve("dev")).trim() else majorMinor
  }

  /**
   * btrfs and overlay report an anonymous `0:N` device for every file, so the disk then comes from
   * the mount's source device in mountinfo.
   */
  fun under(path: Path, sys: Path, mountinfo: Path = Path.of("/proc/self/mountinfo")): String {
    val device = majorMinor(Files.getAttribute(path, "unix:dev") as Long)
    if (!device.startsWith(ANONYMOUS_MAJOR)) {
      return wholeDisk(sys, device)
    }
    val source = mountSource(Files.readAllLines(mountinfo), path.toAbsolutePath().normalize().toString())
      ?: error("no mount with a /dev source holds $path")
    return wholeDisk(sys, Files.readString(sys.resolve("class/block/${source.removePrefix("/dev/")}/dev")).trim())
  }

  /** The `/dev/...` source of the deepest mount point that holds [path]. */
  fun mountSource(mountinfo: List<String>, path: String): String? =
    mountinfo.mapNotNull { line ->
      val fields = line.split(" ")
      val separator = fields.indexOf("-")
      val mountPoint = fields.getOrNull(MOUNT_POINT_FIELD) ?: return@mapNotNull null
      val source = fields.getOrNull(separator + 2)
      if (separator < 0 || source == null || !source.startsWith("/dev/")) return@mapNotNull null
      if (path == mountPoint || path.startsWith(mountPoint.trimEnd('/') + "/")) mountPoint to source else null
    }.maxByOrNull { it.first.length }?.second

  private const val ANONYMOUS_MAJOR = "0:"
  private const val MOUNT_POINT_FIELD = 4
}

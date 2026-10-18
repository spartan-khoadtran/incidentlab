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

  fun under(path: Path, sys: Path): String = wholeDisk(sys, majorMinor(Files.getAttribute(path, "unix:dev") as Long))
}

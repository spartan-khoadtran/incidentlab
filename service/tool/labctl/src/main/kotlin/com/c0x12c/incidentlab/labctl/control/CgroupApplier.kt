package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sets a container's write IOPS limit on one disk by writing its cgroup v2 `io.max` file. The
 * Docker API can only set that limit when a container is created, so the controller writes the
 * file itself through the host cgroup mount. A container restart gets a new cgroup, and the next
 * tick writes the limit again.
 */
class CgroupApplier(
  private val containerId: (String) -> String?,
  private val cgroupRoot: Path,
  private val disk: String
) : Applier, Logging {

  private var lastProblem: String? = null

  override fun apply(desired: Desired) {
    for ((container, settings) in desired.docker) {
      if (!settings.containsKey(WRITE_IOPS)) {
        continue
      }
      try {
        ensureWriteIops(container, limit(settings[WRITE_IOPS]))
        lastProblem = null
      } catch (e: Exception) {
        val problem = "cgroup $container: ${e.message}"
        if (problem != lastProblem) {
          warn { problem }
        }
        lastProblem = problem
      }
    }
  }

  private fun ensureWriteIops(container: String, wanted: String) {
    val id = containerId(container) ?: return
    val file = cgroupDir(id)?.resolve(IO_MAX) ?: throw IllegalStateException("no cgroup directory for $id under $cgroupRoot")
    if (writeIops(Files.readString(file), disk) == wanted) {
      return
    }
    Files.writeString(file, "$disk wiops=$wanted\n")
    info { "cgroup $container: $disk wiops=$wanted" }
  }

  private fun cgroupDir(id: String): Path? =
    listOf(cgroupRoot.resolve("docker/$id"), cgroupRoot.resolve("system.slice/docker-$id.scope")).firstOrNull { Files.isDirectory(it) }

  companion object : Logging {
    const val WRITE_IOPS = "write_iops"
    const val UNLIMITED = "max"
    private const val IO_MAX = "io.max"

    /** A positive number is a limit; anything else (0, null, "max") removes it. */
    fun limit(value: Any?): String = (value as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: UNLIMITED

    /** The `wiops` of one disk in an `io.max` file. A disk with no line has no limit. */
    fun writeIops(ioMax: String, disk: String): String =
      ioMax.lines().map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.firstOrNull() == disk }
        ?.drop(1)?.map { it.split("=", limit = 2) }?.firstOrNull { it.size == 2 && it[0] == "wiops" }?.get(1)
        ?: UNLIMITED

    /** Null, with one log line, when the host cgroup tree, the volumes or sysfs are not mounted. */
    fun ifAvailable(containerId: (String) -> String?, cgroupRoot: Path, volumes: Path, sys: Path, mountinfo: Path = Path.of("/proc/self/mountinfo")): CgroupApplier? {
      val missing = listOf(cgroupRoot, volumes, sys).filterNot { Files.isDirectory(it) }
      if (missing.isNotEmpty()) {
        info { "not mounted: ${missing.joinToString()}: disk IOPS exercises are disabled" }
        return null
      }
      return try {
        val disk = BlockDevice.under(volumes, sys, mountinfo)
        info { "disk IOPS limits apply to device $disk (the disk under $volumes)" }
        CgroupApplier(containerId, cgroupRoot, disk)
      } catch (e: Exception) {
        info { "cannot find the disk under $volumes (${e.message}): disk IOPS exercises are disabled" }
        null
      }
    }
  }
}

package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.LabJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The container settings the running controller can apply. An exercise that needs another one is never started. */
fun interface Capabilities {
  /** The `docker` settings keys the controller applies, or null when no controller said, and then every key counts. */
  fun dockerKeys(): Set<String>?

  companion object {
    val ALL = Capabilities { null }
  }
}

/**
 * The daemon writes its appliers' keys here once at start, beside the state file, and every CLI
 * command and the API read it. A missing or broken file means every key is supported, so the dev
 * `labctl api` and an older volume work as before.
 */
class CapabilitiesFile(private val path: Path) : Capabilities {

  private data class Content(val dockerKeys: Set<String> = emptySet())

  override fun dockerKeys(): Set<String>? =
    try {
      LabJson.mapper.readValue(Files.readString(path), Content::class.java).dockerKeys
    } catch (e: Exception) {
      null
    }

  fun write(dockerKeys: Set<String>) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temp, LabJson.mapper.writeValueAsString(Content(dockerKeys.toSortedSet())))
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  companion object {
    const val FILE_NAME = "capabilities.json"

    fun beside(stateFile: Path): CapabilitiesFile = CapabilitiesFile(stateFile.resolveSibling(FILE_NAME))
  }
}

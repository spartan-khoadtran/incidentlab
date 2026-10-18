package com.c0x12c.incidentlab.labctl.state

import com.c0x12c.incidentlab.labctl.LabJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The state file on the shared volume. Written atomically, because the daemon reads it every 3 s. */
class StateStore(private val path: Path) {

  fun load(): LabState =
    try {
      LabJson.mapper.readValue(Files.readString(path), LabState::class.java)
    } catch (e: Exception) {
      LabState()
    }

  fun save(state: LabState) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temp, LabJson.mapper.writeValueAsString(state))
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  fun update(change: (LabState) -> LabState): LabState = change(load()).also(::save)
}

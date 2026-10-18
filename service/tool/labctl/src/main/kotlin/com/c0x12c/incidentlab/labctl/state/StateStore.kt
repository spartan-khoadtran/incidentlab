package com.c0x12c.incidentlab.labctl.state

import com.c0x12c.incidentlab.labctl.LabJson
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The state file on the shared volume. The daemon reads it every 3 s without a lock, so every write
 * is a rename of a full file. The CLI (one process per command) and the daemon's HTTP API both
 * write it, so every read-modify-write holds an exclusive lock on a sibling lock file.
 */
class StateStore(private val path: Path) {

  private val lockFile: Path = path.resolveSibling(path.fileName.toString() + ".lock")

  fun load(): LabState =
    try {
      LabJson.mapper.readValue(Files.readString(path), LabState::class.java)
    } catch (e: Exception) {
      LabState()
    }

  fun save(state: LabState) {
    withLock { _, write -> write(state) }
  }

  fun update(change: (LabState) -> LabState): LabState = withLock { state, write -> change(state).also(write) }

  /**
   * Reads the state and runs [block] while no other thread or process can change the file. The
   * block calls `write` for the state it wants on disk, or does not call it to leave the file alone.
   */
  fun <T> withLock(block: (state: LabState, write: (LabState) -> Unit) -> T): T {
    val local = LOCAL_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize()) { ReentrantLock() }
    return local.withLock {
      if (local.holdCount > 1) {
        block(load(), ::writeFile)
      } else {
        Files.createDirectories(lockFile.toAbsolutePath().parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
          channel.lock().use { block(load(), ::writeFile) }
        }
      }
    }
  }

  private fun writeFile(state: LabState) {
    val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temp, LabJson.mapper.writeValueAsString(state))
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private companion object {
    /** A JVM may hold one lock per file, so threads of one process queue here before the file lock. */
    val LOCAL_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
  }
}

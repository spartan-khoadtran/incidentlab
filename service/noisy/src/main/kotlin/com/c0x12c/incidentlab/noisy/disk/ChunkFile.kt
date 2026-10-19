package com.c0x12c.incidentlab.noisy.disk

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/** One file that takes fixed-size chunks, each followed by an fsync, and starts over at the wrap size. */
class ChunkFile(
  dir: Path,
  private val chunkBytes: Int = CHUNK_BYTES,
  private val wrapBytes: Long = WRAP_BYTES
) : AutoCloseable {

  private val chunk: ByteBuffer = ByteBuffer.wrap(Random.nextBytes(chunkBytes))
  private val channel: FileChannel = Files.createDirectories(dir).resolve(FILE_NAME)
    .let { FileChannel.open(it, StandardOpenOption.CREATE, StandardOpenOption.WRITE) }

  val size: Long
    get() = channel.size()

  /** Writes one chunk, fsyncs the file, and returns the seconds the fsync took. */
  fun writeChunk(): Double {
    if (channel.position() + chunkBytes > wrapBytes) {
      channel.truncate(0)
      channel.position(0)
    }
    val buffer = chunk.duplicate()
    while (buffer.hasRemaining()) {
      channel.write(buffer)
    }
    val start = System.nanoTime()
    channel.force(true)
    return (System.nanoTime() - start) / NANOS_PER_SECOND
  }

  override fun close() {
    channel.close()
  }

  companion object {
    const val CHUNK_BYTES = 1 shl 20
    const val WRAP_BYTES = 256L shl 20
    const val FILE_NAME = "noisy.dat"
    private const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

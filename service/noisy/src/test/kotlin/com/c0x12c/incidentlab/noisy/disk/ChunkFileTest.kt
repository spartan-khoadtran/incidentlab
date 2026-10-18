package com.c0x12c.incidentlab.noisy.disk

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChunkFileTest {

  @Test
  fun `each chunk grows the file and reports the fsync time`(@TempDir dir: Path) {
    ChunkFile(dir.resolve("data"), chunkBytes = 1_024, wrapBytes = 4_096).use { file ->
      assertThat(file.writeChunk()).isGreaterThanOrEqualTo(0.0)
      file.writeChunk()

      assertThat(file.size).isEqualTo(2_048L)
    }
  }

  @Test
  fun `the file starts over once the next chunk would pass the wrap size`(@TempDir dir: Path) {
    ChunkFile(dir, chunkBytes = 1_024, wrapBytes = 4_096).use { file ->
      repeat(4) { file.writeChunk() }
      assertThat(file.size).isEqualTo(4_096L)

      file.writeChunk()

      assertThat(file.size).isEqualTo(1_024L)
    }
  }
}

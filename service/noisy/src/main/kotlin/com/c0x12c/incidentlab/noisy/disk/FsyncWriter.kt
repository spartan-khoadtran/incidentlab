package com.c0x12c.incidentlab.noisy.disk

import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.noisy.config.NoisyDiskConfig
import com.c0x12c.incidentlab.noisy.factory.NoisyFaults
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Writes 1 MB chunks with an fsync after each, at `fsync_mb_per_s`, on the disk Postgres uses. When
 * the disk cannot keep up, it writes as fast as the fsyncs return.
 */
@Singleton
class FsyncWriter(
  private val faults: Faults,
  private val config: NoisyDiskConfig,
  registry: MeterRegistry
) : ApplicationEventListener<StartupEvent>, Logging {

  private val fsyncs = LabCounter(registry, "noisy_fsync", "fsync calls by the noisy disk writer").labels()
  private val fsyncSeconds = LabCounter(registry, "noisy_fsync_seconds", "Seconds the noisy disk writer spent in fsync").labels()

  @Volatile
  private var running = true

  private val thread = Thread(::run, "fsync-writer").apply { isDaemon = true }

  override fun onApplicationEvent(event: StartupEvent) {
    thread.start()
  }

  @PreDestroy
  fun stop() {
    running = false
  }

  private fun run() {
    var file: ChunkFile? = null
    var rate = 0.0
    while (running) {
      val wanted = faults.double(NoisyFaults.FSYNC_MB_PER_S).coerceAtLeast(0.0)
      if (wanted != rate) {
        info { "fsync writer: $rate -> $wanted MB/s" }
        rate = wanted
      }
      if (rate == 0.0) {
        file?.close()
        file = null
        LockSupport.parkNanos(IDLE_NANOS)
        continue
      }
      try {
        val chunks = file ?: ChunkFile(Path.of(config.dir)).also { file = it }
        val start = System.nanoTime()
        fsyncSeconds.increment(chunks.writeChunk())
        fsyncs.increment()
        val left = (NANOS_PER_SECOND / rate).toLong() - (System.nanoTime() - start)
        if (left > 0) {
          LockSupport.parkNanos(left)
        }
      } catch (e: Exception) {
        warn { "fsync writer: ${e.message}" }
        file?.close()
        file = null
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1))
      }
    }
    file?.close()
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0
    val IDLE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(200)
  }
}

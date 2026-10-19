package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import jdk.jfr.consumer.RecordingStream

/**
 * Counts virtual threads that park while pinned to their carrier, from the JFR event
 * `jdk.VirtualThreadPinned`. The JDK default threshold is 20 ms; this stream lowers it to [threshold]
 * so short pins on a busy service still count. Nothing else is recorded.
 */
class JfrMetrics(
  private val threshold: Duration = Duration.ofMillis(1)
) : MeterBinder, AutoCloseable {

  private val pinned = AtomicLong()
  private val pinnedNanos = AtomicLong()

  @Volatile
  private var stream: AutoCloseable? = null

  override fun bindTo(registry: MeterRegistry) {
    FunctionCounter.builder("jvm_virtual_thread_pinned", pinned) { it.get().toDouble() }
      .description("Virtual threads that parked while pinned to their carrier thread")
      .register(registry)
    FunctionCounter.builder("jvm_virtual_thread_pinned_seconds", pinnedNanos) { it.get() / NANOS_PER_SECOND }
      .description("Time virtual threads spent parked while pinned, so their carrier could run nothing else")
      .register(registry)
    start()
  }

  override fun close() {
    stream?.close()
  }

  private fun start() {
    stream = try {
      RecordingStream().apply {
        enable(PINNED_EVENT).withThreshold(threshold)
        setMaxAge(RETENTION)
        onEvent(PINNED_EVENT) { event ->
          pinned.incrementAndGet()
          pinnedNanos.addAndGet(event.duration.toNanos())
        }
        startAsync()
      }
    } catch (e: Throwable) {
      // A runtime without the jdk.jfr module, or with JFR disabled, keeps the counters at zero.
      null
    }
  }

  private companion object {
    const val PINNED_EVENT = "jdk.VirtualThreadPinned"
    const val NANOS_PER_SECOND = 1_000_000_000.0
    val RETENTION: Duration = Duration.ofMinutes(2)
  }
}

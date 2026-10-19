package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The three process gauges the Runtime (process) panels read, named as the Prometheus client
 * libraries name them so the panels work unchanged: CPU seconds, resident memory, start time.
 */
class ProcessMetrics(
  private val status: Path = Path.of("/proc/self/status")
) : MeterBinder {

  override fun bindTo(registry: MeterRegistry) {
    val os = ManagementFactory.getOperatingSystemMXBean()
    if (os is com.sun.management.OperatingSystemMXBean) {
      FunctionCounter.builder("process_cpu_seconds", os) { it.processCpuTime / NANOS_PER_SECOND }
        .description("Total user and system CPU time spent by the process")
        .register(registry)
    }
    val runtime = ManagementFactory.getRuntimeMXBean()
    Gauge.builder("process_start_time_seconds", runtime) { it.startTime / MILLIS_PER_SECOND }
      .description("Start time of the process since unix epoch")
      .register(registry)
    Gauge.builder("process_resident_memory_bytes", this) { it.residentBytes() }
      .description("Resident memory size of the process")
      .register(registry)
  }

  internal fun residentBytes(): Double {
    if (!Files.isReadable(status)) {
      return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()).toDouble() }
    }
    val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return 0.0
    val kilobytes = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: return 0.0
    return kilobytes * BYTES_PER_KILOBYTE
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0
    const val MILLIS_PER_SECOND = 1_000.0
    const val BYTES_PER_KILOBYTE = 1_024.0
  }
}

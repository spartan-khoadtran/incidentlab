package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig

/**
 * The JVM runtime layer: heap by pool against its max, GC pauses as a histogram with the shared
 * latency buckets, allocation and promotion, live data after GC, and platform thread counts.
 * Bind it before any other binder: the bucket filter only shapes meters registered after it.
 */
class JvmMetrics : MeterBinder, AutoCloseable {

  private val gc = JvmGcMetrics()

  override fun bindTo(registry: MeterRegistry) {
    registry.config().meterFilter(GC_PAUSE_BUCKETS)
    gc.bindTo(registry)
    JvmMemoryMetrics().bindTo(registry)
    JvmThreadMetrics().bindTo(registry)
  }

  override fun close() {
    gc.close()
  }

  internal companion object {
    const val GC_PAUSE = "jvm.gc.pause"
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    val GC_PAUSE_BUCKETS: MeterFilter = object : MeterFilter {
      override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig {
        if (id.name != GC_PAUSE) {
          return config
        }
        val edges = LatencyBuckets.LATENCY.map { it * NANOS_PER_SECOND }
        return DistributionStatisticConfig.builder()
          .serviceLevelObjectives(*edges.toDoubleArray())
          .minimumExpectedValue(edges.first())
          .maximumExpectedValue(edges.last())
          .build()
          .merge(config)
      }
    }
  }
}

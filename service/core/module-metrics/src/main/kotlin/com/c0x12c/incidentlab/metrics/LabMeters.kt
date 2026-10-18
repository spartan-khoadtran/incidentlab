package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Prometheus-shaped meters on top of Micrometer: one family, a fixed label key list, and a child
 * per label value tuple. Names are exported as written, so a counter is declared without the
 * `_total` the Prometheus registry appends and a histogram ends in `_seconds` already.
 */
class LabCounter(
  private val registry: MeterRegistry,
  private val name: String,
  private val description: String,
  private val labelKeys: List<String> = emptyList()
) {
  private val children = ConcurrentHashMap<List<String>, Counter>()

  fun labels(vararg values: String): Counter =
    children.computeIfAbsent(values.toList()) {
      Counter.builder(name).description(description).tags(tags(labelKeys, it)).register(registry)
    }

  fun increment(vararg values: String) {
    labels(*values).increment()
  }
}

class LabHistogram(
  private val registry: MeterRegistry,
  private val name: String,
  private val description: String,
  buckets: DoubleArray,
  private val labelKeys: List<String> = emptyList()
) {
  private val children = ConcurrentHashMap<List<String>, Timer>()
  private val edges: Array<Duration> = buckets.map(::nanos).map(Duration::ofNanos).toTypedArray()

  // A Timer, not a DistributionSummary: Micrometer rounds a summary value up to a whole number before bucketing.
  fun labels(vararg values: String): Timer =
    children.computeIfAbsent(values.toList()) {
      Timer.builder(name)
        .description(description)
        .serviceLevelObjectives(*edges)
        .minimumExpectedValue(edges.first())
        .maximumExpectedValue(edges.last())
        .tags(tags(labelKeys, it))
        .register(registry)
    }

  fun observe(seconds: Double, vararg values: String) {
    labels(*values).record(Duration.ofNanos(nanos(seconds)))
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0

    fun nanos(seconds: Double): Long = (seconds * NANOS_PER_SECOND).toLong()
  }
}

class LabGauge(
  private val registry: MeterRegistry,
  private val name: String,
  private val description: String,
  private val labelKeys: List<String> = emptyList()
) {
  private val children = ConcurrentHashMap<List<String>, AtomicReference<Double>>()

  fun set(value: Double, vararg values: String) {
    children.computeIfAbsent(values.toList()) {
      AtomicReference(0.0).also { holder ->
        Gauge.builder(name, holder) { it.get() }.description(description).tags(tags(labelKeys, it)).register(registry)
      }
    }.set(value)
  }

  fun set(value: Int, vararg values: String) {
    set(value.toDouble(), *values)
  }
}

private fun tags(keys: List<String>, values: List<String>): Tags {
  require(keys.size == values.size) { "expected ${keys.size} label values for $keys, got $values" }
  return Tags.of(*keys.zip(values).flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
}

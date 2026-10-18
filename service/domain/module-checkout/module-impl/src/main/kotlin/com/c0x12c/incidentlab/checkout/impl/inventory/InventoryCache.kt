package com.c0x12c.incidentlab.checkout.impl.inventory

import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.span
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Tracer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ln
import kotlin.random.Random

/**
 * The v2.40 inventory cache: every checkout refreshes it under one global lock. The lock is a
 * software resource with the three USE numbers: `app_lock_held_seconds` (rate() is utilization),
 * `app_lock_waiting` and `app_lock_wait_seconds` (saturation). A hold time of 0 skips all of it.
 * Hold times are exponential around `lock_ms`, so with Poisson arrivals the lock is an M/M/1 queue.
 */
class InventoryCache(
  private val faults: Faults,
  private val tracer: Tracer,
  registry: MeterRegistry,
  private val random: Random = Random.Default
) {

  private val lock = ReentrantLock(true)
  private val waiting = AtomicInteger()
  private val wait = LabHistogram(registry, "app_lock_wait_seconds", "Time a checkout waited for the inventory lock", LatencyBuckets.LATENCY)
  private val held: Counter = Counter.builder("app_lock_held_seconds").description("Time the inventory lock was held; rate() is its utilization").register(registry)

  init {
    Gauge.builder("app_lock_waiting", waiting) { it.get().toDouble() }.description("Checkouts waiting for the inventory lock").register(registry)
  }

  val waitingNow: Int
    get() = waiting.get()

  fun refresh() {
    val hold = faults.double(CheckoutFaults.LOCK_MS)
    if (hold <= 0) {
      return
    }
    tracer.span("inventory.refresh") {
      val acquired = tracer.span("inventory.lock.acquire") {
        val start = System.nanoTime()
        waiting.incrementAndGet()
        try {
          lock.lock()
        } finally {
          waiting.decrementAndGet()
        }
        val now = System.nanoTime()
        wait.observe((now - start) / NANOS_PER_SECOND)
        now
      }
      try {
        Thread.sleep(holdTime(hold))
      } finally {
        lock.unlock()
        held.increment((System.nanoTime() - acquired) / NANOS_PER_SECOND)
      }
    }
  }

  internal fun holdTime(meanMillis: Double): Duration =
    Duration.ofNanos((-ln(1 - random.nextDouble()) * meanMillis).coerceAtMost(meanMillis * MAX_HOLD_FACTOR).times(NANOS_PER_MILLI).toLong())

  private companion object {
    const val MAX_HOLD_FACTOR = 10.0
    const val NANOS_PER_MILLI = 1_000_000.0
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

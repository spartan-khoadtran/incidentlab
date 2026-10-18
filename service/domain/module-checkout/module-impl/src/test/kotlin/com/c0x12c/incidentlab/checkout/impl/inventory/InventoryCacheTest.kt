package com.c0x12c.incidentlab.checkout.impl.inventory

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.faults.Faults
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.random.Random
import org.junit.jupiter.api.Test

class InventoryCacheTest {

  private val tracer = OpenTelemetry.noop().getTracer("test")

  @Test
  fun `a hold time of zero touches neither the lock nor the meters`() {
    val registry = SimpleMeterRegistry()
    val cache = InventoryCache(Faults(CheckoutFaults.DEFAULTS), tracer, registry)

    cache.refresh()

    assertThat(registry.find("app_lock_wait_seconds").timer()?.count()).isNull()
    assertThat(registry.find("app_lock_held_seconds").counter()!!.count()).isEqualTo(0.0)
    assertThat(cache.waitingNow).isEqualTo(0)
  }

  @Test
  fun `checkouts queue on the one lock, and the meters see the wait and the hold`() {
    val registry = SimpleMeterRegistry()
    val faults = Faults(CheckoutFaults.DEFAULTS).also { it.replace(mapOf(CheckoutFaults.LOCK_MS to HOLD_MS)) }
    val cache = InventoryCache(faults, tracer, registry, OneMeanHold)

    val start = CountDownLatch(1)
    val threads = (1..THREADS).map {
      thread {
        start.await()
        cache.refresh()
      }
    }
    start.countDown()
    threads.forEach { it.join() }

    val wait = registry.find("app_lock_wait_seconds").timer()!!
    val held = registry.find("app_lock_held_seconds").counter()!!
    assertThat(wait.count()).isEqualTo(THREADS.toLong())
    // Holds are serial, so the last thread waited for every other one at least.
    assertThat(wait.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo((THREADS - 1) * HOLD_MS * SLACK)
    assertThat(held.count()).isGreaterThan(THREADS * HOLD_MS * SLACK / MILLIS_PER_SECOND)
    assertThat(cache.waitingNow).isEqualTo(0)
    assertThat(registry.find("app_lock_waiting").gauge()!!.value()).isEqualTo(0.0)
  }

  @Test
  fun `hold times are exponential around the mean and capped at ten means`() {
    val cache = InventoryCache(Faults(CheckoutFaults.DEFAULTS), tracer, SimpleMeterRegistry(), Random(9))
    val holds = (1..SAMPLES).map { cache.holdTime(HOLD_MS).toNanos() / NANOS_PER_MILLI }

    assertThat(holds.average()).isBetween(HOLD_MS * 0.97, HOLD_MS * 1.03)
    // An exponential's standard deviation equals its mean; a fixed hold would give 0.
    val spread = Math.sqrt(holds.sumOf { (it - holds.average()) * (it - holds.average()) } / holds.size)
    assertThat(spread).isBetween(HOLD_MS * 0.9, HOLD_MS * 1.1)
    assertThat(holds.max()).isLessThanOrEqualTo(HOLD_MS * 10)
  }

  /** Every draw lands exactly on the mean: -ln(1 - u) = 1. */
  private object OneMeanHold : Random() {
    override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)

    override fun nextDouble(): Double = 1 - exp(-1.0)
  }

  private companion object {
    const val THREADS = 4
    const val HOLD_MS = 40.0
    const val SLACK = 0.9
    const val SAMPLES = 20_000
    const val MILLIS_PER_SECOND = 1_000.0
    const val NANOS_PER_MILLI = 1_000_000.0
  }
}

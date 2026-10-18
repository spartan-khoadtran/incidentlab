package com.c0x12c.incidentlab.checkout.impl.runtime

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * The GC-pressure fault: an on-heap store of tiny objects that holds about `retain_mb`. Each
 * request writes `churn_kb` of new objects over random old ones. An object lives long enough to be
 * promoted to the old generation, then dies there, so the old generation fills with garbage on top
 * of a large live set, and every full collection has millions of live objects to mark.
 */
class HeapPressure(
  registry: MeterRegistry,
  private val random: Random = Random.Default
) {

  private val lock = ReentrantLock()
  private var slots: Array<Any?> = arrayOfNulls(0)
  private var filled = 0
  private val retained = AtomicLong()

  init {
    Gauge.builder("app_retained_bytes", retained) { it.get().toDouble() }
      .description("Bytes the GC-pressure fault keeps alive on the heap")
      .register(registry)
  }

  fun write(retainMb: Int, churnKb: Int) {
    if (retainMb <= 0 || churnKb <= 0) {
      if (retained.get() > 0) {
        clear()
      }
      return
    }
    val capacity = (retainMb * BYTES_PER_MB / BYTES_PER_ENTRY).toInt()
    val count = (churnKb * BYTES_PER_KB / BYTES_PER_ENTRY).toInt()
    lock.withLock {
      if (slots.size != capacity) {
        slots = slots.copyOf(capacity)
        filled = filled.coerceAtMost(capacity)
      }
      repeat(count) {
        val index = if (filled < capacity) filled++ else random.nextInt(capacity)
        slots[index] = Any()
      }
      retained.set(filled * BYTES_PER_ENTRY)
    }
  }

  fun clear() {
    lock.withLock {
      slots = arrayOfNulls(0)
      filled = 0
      retained.set(0)
    }
  }

  private companion object {
    // A bare Object is 16 bytes and its slot in the array 4 more (compressed references under 32 GB).
    const val BYTES_PER_ENTRY = 20L
    const val BYTES_PER_KB = 1_024L
    const val BYTES_PER_MB = 1_048_576L
  }
}

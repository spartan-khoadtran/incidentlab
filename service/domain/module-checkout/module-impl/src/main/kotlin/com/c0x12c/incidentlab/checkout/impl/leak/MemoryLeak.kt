package com.c0x12c.incidentlab.checkout.impl.leak

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import sun.misc.Unsafe

/**
 * The memory-leak fault. Off heap on purpose: a heap leak ends in a Java OutOfMemoryError the JVM
 * reports itself, while the exercise is about the container limit, the kernel OOM killer and the
 * restart that follows. Native memory grows the RSS until the cgroup limit, like a leak in any
 * runtime without a managed heap would.
 */
class MemoryLeak(
  registry: MeterRegistry
) {

  private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
  private val slabs = ArrayDeque<Long>()
  private var slabUsed = SLAB_BYTES
  private val leaked = AtomicLong()

  init {
    Gauge.builder("app_leaked_bytes", leaked) { it.get().toDouble() }
      .description("Bytes kept by the memory-leak fault")
      .register(registry)
  }

  /** Leaks [kilobytes] by writing them into the current slab, so the RSS grows by the same amount. */
  fun leak(kilobytes: Int) {
    if (kilobytes <= 0) {
      return
    }
    val size = (kilobytes * BYTES_PER_KILOBYTE).coerceAtMost(SLAB_BYTES)
    synchronized(slabs) {
      if (slabUsed + size > SLAB_BYTES) {
        slabs.add(unsafe.allocateMemory(SLAB_BYTES))
        slabUsed = 0
      }
      unsafe.setMemory(slabs.last + slabUsed, size, 1)
      slabUsed += size
      leaked.addAndGet(size)
    }
  }

  fun clear() {
    synchronized(slabs) {
      if (slabs.isEmpty()) {
        return
      }
      slabs.forEach { unsafe.freeMemory(it) }
      slabs.clear()
      slabUsed = SLAB_BYTES
      leaked.set(0)
    }
  }

  private companion object {
    const val BYTES_PER_KILOBYTE = 1_024L

    // Above glibc's largest mmap threshold (32 MB), so every slab is its own mapping and free() gives the memory back to the OS.
    const val SLAB_BYTES = 64L * 1_048_576
  }
}

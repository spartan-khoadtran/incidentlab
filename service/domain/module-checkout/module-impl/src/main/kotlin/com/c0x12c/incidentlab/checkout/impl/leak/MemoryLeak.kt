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
  private val blocks = ArrayDeque<Block>()
  private val leaked = AtomicLong()

  init {
    Gauge.builder("app_leaked_bytes", leaked) { it.get().toDouble() }
      .description("Bytes kept by the memory-leak fault")
      .register(registry)
  }

  fun leak(kilobytes: Int) {
    if (kilobytes <= 0) {
      return
    }
    val size = kilobytes * BYTES_PER_KILOBYTE
    val address = unsafe.allocateMemory(size)
    unsafe.setMemory(address, size, 1)
    synchronized(blocks) {
      blocks.add(Block(address, size))
      leaked.addAndGet(size)
    }
  }

  fun clear() {
    synchronized(blocks) {
      if (blocks.isEmpty()) {
        return
      }
      blocks.forEach { unsafe.freeMemory(it.address) }
      blocks.clear()
      leaked.set(0)
    }
  }

  private class Block(val address: Long, val size: Long)

  private companion object {
    const val BYTES_PER_KILOBYTE = 1_024L
  }
}

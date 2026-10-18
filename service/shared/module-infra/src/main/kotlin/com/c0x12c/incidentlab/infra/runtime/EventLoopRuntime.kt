package com.c0x12c.incidentlab.infra.runtime

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.http.netty.channel.EventLoopGroupRegistry
import io.micronaut.runtime.server.event.ServerStartupEvent
import io.netty.channel.EventLoop
import jakarta.inject.Singleton
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The Netty event loop as the service's single-threaded runtime. Every request's bytes pass
 * through it, so CPU work parked on it ([onEventLoop]) stalls every other request, the way CPU
 * work on a Python asyncio loop or a Node process does. The lag probe schedules a 50 ms timer on
 * the loop and records how late it fires: that lateness is the loop's saturation.
 */
@Singleton
class EventLoopRuntime(
  registry: MeterRegistry,
  private val eventLoops: EventLoopGroupRegistry
) : ApplicationEventListener<ServerStartupEvent>, Logging {

  private val lag = LabHistogram(registry, "event_loop_lag_seconds", "How late the event loop wakes a 50ms timer", LatencyBuckets.LOOP_LAG)

  override fun onApplicationEvent(event: ServerStartupEvent) {
    info { "event loop lag probe started" }
    scheduleProbe()
  }

  /** Runs [block] on the event loop thread and waits for the result. */
  fun <T> onEventLoop(block: () -> T): T {
    val loop = loop()
    return if (loop.inEventLoop()) block() else loop.submit(Callable(block)).get()
  }

  private fun loop(): EventLoop = eventLoops.defaultEventLoopGroup.next()

  private fun scheduleProbe() {
    val scheduledAt = System.nanoTime()
    loop().schedule({
      val late = (System.nanoTime() - scheduledAt) / NANOS_PER_SECOND - PROBE_INTERVAL_SECONDS
      lag.observe(late.coerceAtLeast(0.0))
      scheduleProbe()
    }, PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
  }

  private companion object {
    const val PROBE_INTERVAL_MILLIS = 50L
    const val PROBE_INTERVAL_SECONDS = 0.05
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

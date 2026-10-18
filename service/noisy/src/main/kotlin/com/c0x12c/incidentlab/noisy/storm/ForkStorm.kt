package com.c0x12c.incidentlab.noisy.storm

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.noisy.factory.NoisyFaults
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Starts `fork_rps` short-lived processes (`/usr/bin/true`) per second and reaps every one. Each lives a few
 * milliseconds, so `top` never shows one, while the host's fork counter (`node_forks_total`)
 * climbs by the same rate. Ten ticks a second, each starting its share of the second.
 */
@Singleton
class ForkStorm(
  private val faults: Faults,
  registry: MeterRegistry
) : ApplicationEventListener<StartupEvent>, Logging {

  private val forks: Counter = Counter.builder("noisy_forks").description("Short-lived processes started by the fork storm").register(registry)
  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "fork-storm").apply { isDaemon = true }
  }
  private var due = 0.0
  private var lastRate = 0

  @Volatile
  private var unavailable = false

  val started: Long
    get() = forks.count().toLong()

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleAtFixedRate(::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  /** One tenth of a second of the storm: starts the processes due by now, then waits for all of them to exit. */
  internal fun tick() {
    val perSecond = faults.int(NoisyFaults.FORK_RPS).coerceIn(0, MAX_FORK_RPS)
    if (perSecond != lastRate) {
      info { "fork storm: $lastRate -> $perSecond forks/s" }
      lastRate = perSecond
    }
    if (perSecond == 0 || unavailable) {
      due = 0.0
      return
    }
    due += perSecond * TICK_MILLIS / MILLIS_PER_SECOND
    val count = due.toInt()
    due -= count
    val children = (1..count).mapNotNull { start() }
    for (child in children) {
      if (!child.waitFor(REAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        child.destroyForcibly().waitFor()
      }
      forks.increment()
    }
  }

  private fun start(): Process? =
    try {
      ProcessBuilder(COMMAND).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start()
    } catch (e: IOException) {
      unavailable = true
      warn { "fork storm off, cannot start $COMMAND: ${e.message}" }
      null
    }

  private companion object {
    const val COMMAND = "/usr/bin/true"
    const val MAX_FORK_RPS = 200
    const val TICK_MILLIS = 100L
    const val MILLIS_PER_SECOND = 1_000.0
    const val REAP_TIMEOUT_SECONDS = 5L
  }
}

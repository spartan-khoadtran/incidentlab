package com.c0x12c.incidentlab.noisy.burner

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.noisy.factory.NoisyFaults
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Keeps exactly `burners` platform threads spinning. Reconciled once a second against the fault flag. */
@Singleton
class BurnerPool(
  private val faults: Faults
) : ApplicationEventListener<StartupEvent>, Logging {

  private val burners = mutableListOf<Burner>()
  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "burner-reconcile").apply { isDaemon = true }
  }

  val running: Int
    get() = synchronized(burners) { burners.size }

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleAtFixedRate(::reconcile, 0, 1, TimeUnit.SECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
    synchronized(burners) {
      burners.forEach { it.stop() }
      burners.clear()
    }
  }

  private fun reconcile() {
    val wanted = faults.int(NoisyFaults.BURNERS).coerceAtLeast(0)
    synchronized(burners) {
      if (wanted != burners.size) {
        info { "burners: ${burners.size} -> $wanted" }
      }
      while (burners.size < wanted) {
        burners.add(Burner(burners.size).also { it.start() })
      }
      while (burners.size > wanted) {
        burners.removeAt(burners.size - 1).stop()
      }
    }
  }

  private class Burner(index: Int) {
    @Volatile
    private var running = true
    private val thread = Thread({
      var x = 0L
      while (running) {
        x = (x * 31 + 7) % MODULUS
      }
    }, "burner-$index").apply { isDaemon = true }

    fun start() {
      thread.start()
    }

    fun stop() {
      running = false
    }
  }

  private companion object {
    const val MODULUS = 1_000_003L
  }
}

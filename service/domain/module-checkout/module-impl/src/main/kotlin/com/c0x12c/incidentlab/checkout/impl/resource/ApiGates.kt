package com.c0x12c.incidentlab.checkout.impl.resource

import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.checkout.impl.leak.MemoryLeak
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.metrics.LabGauge
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.utility.ResizableGate
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The api's two software resources and their USE gauges: the admission gate ahead of the handlers
 * and the pool gate ahead of the real connection pool. Both limits are fault flags, so a refresh
 * tick re-reads them and wakes waiters a raised limit now allows.
 */
@Singleton
class ApiGates(
  private val faults: Faults,
  private val leak: MemoryLeak,
  registry: MeterRegistry
) : ApplicationEventListener<StartupEvent> {

  val admission = ResizableGate { faults.int(CheckoutFaults.ADMISSION_LIMIT) }
  val pool = ResizableGate { faults.int(CheckoutFaults.POOL_MAX) }

  val admissionWait = LabHistogram(registry, "app_admission_wait_seconds", "Time a request waited for an admission slot", LatencyBuckets.LATENCY)
  val poolAcquire = LabHistogram(registry, "db_pool_acquire_seconds", "Time waiting for a pooled connection", LatencyBuckets.LATENCY)
  val poolTimeouts: Counter = Counter.builder("db_pool_timeouts").description("Requests that gave up waiting for a connection").register(registry)

  private val admissionWaiting = LabGauge(registry, "app_admission_waiting", "Requests waiting for an admission slot")
  private val admissionInflight = LabGauge(registry, "app_admission_inflight", "Requests holding an admission slot")
  private val admissionLimit = LabGauge(registry, "app_admission_limit", "Admission limit (max concurrent requests)")
  private val poolMax = LabGauge(registry, "db_pool_max", "Pool size (max connections)")
  private val poolInUse = LabGauge(registry, "db_pool_in_use", "Connections checked out")
  private val poolWaiting = LabGauge(registry, "db_pool_waiting", "Requests waiting for a connection")

  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "api-gates").apply { isDaemon = true }
  }

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleAtFixedRate(::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  private fun tick() {
    admissionLimit.set(faults.int(CheckoutFaults.ADMISSION_LIMIT))
    admissionWaiting.set(admission.waiting)
    admissionInflight.set(admission.inFlight)
    poolMax.set(faults.int(CheckoutFaults.POOL_MAX))
    poolInUse.set(pool.inFlight)
    poolWaiting.set(pool.waiting)
    admission.refresh()
    pool.refresh()
    if (faults.int(CheckoutFaults.LEAK_KB) <= 0) {
      leak.clear()
    }
  }

  private companion object {
    const val TICK_MILLIS = 100L
  }
}

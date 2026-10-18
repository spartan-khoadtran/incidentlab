package com.c0x12c.incidentlab.checkout.impl.events

import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.client.dto.checkout.OrderEvent
import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The synthetic producers behind two async exercises: a backfill that replays old orders into the
 * topic at `extra_events_rps`, and one malformed event whenever `poison_nonce` changes.
 */
@Singleton
class BackfillJob(
  private val faults: Faults,
  private val events: OrderEvents
) : ApplicationEventListener<StartupEvent> {

  private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "backfill").apply { isDaemon = true }
  }
  private var sequence = 0L
  private var lastNonce: String? = null

  override fun onApplicationEvent(event: StartupEvent) {
    ticker.scheduleAtFixedRate(::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
  }

  @PreDestroy
  fun stop() {
    ticker.shutdownNow()
  }

  private fun tick() {
    val nonce = faults.string(CheckoutFaults.POISON_NONCE)
    if (!nonce.isNullOrBlank() && nonce != lastNonce) {
      lastNonce = nonce
      val poison = OrderEvent(
        orderId = -1,
        tenant = "partner-x",
        total = 0.0,
        createdMs = System.currentTimeMillis(),
        source = "checkout",
        currency = "XXX",
        poison = nonce
      )
      events.produce(poison, source = "checkout", key = "poison-$nonce")
    }
    val rps = faults.double(CheckoutFaults.EXTRA_EVENTS_RPS)
    if (rps <= 0) {
      return
    }
    val perTick = rps / TICKS_PER_SECOND
    val count = perTick.toInt() + if (Random.nextDouble() < perTick % 1) 1 else 0
    repeat(count) {
      sequence += 1
      val event = OrderEvent(
        orderId = BACKFILL_ID_BASE + sequence,
        tenant = "batch",
        total = 0.0,
        createdMs = System.currentTimeMillis(),
        source = "backfill"
      )
      events.sendAsync(event, source = "backfill", key = sequence.toString())
    }
  }

  private companion object {
    const val TICK_MILLIS = 100L
    const val TICKS_PER_SECOND = 10.0
    const val BACKFILL_ID_BASE = 10_000_000L
  }
}

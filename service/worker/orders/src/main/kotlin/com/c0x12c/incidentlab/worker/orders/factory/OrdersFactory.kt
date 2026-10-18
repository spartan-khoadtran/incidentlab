package com.c0x12c.incidentlab.worker.orders.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

object OrdersFaults {
  const val PROCESS_MS = "process_ms"
  const val FAIL_NONCE = "fail_nonce"
  const val POLL_MS = "poll_ms"

  val DEFAULTS: Map<String, Any?> = mapOf(
    // work per message (sequential per partition)
    PROCESS_MS to 25,
    // messages carrying this poison nonce fail (a bug) and are retried for ever
    FAIL_NONCE to null,
    // how long one consumer.poll blocks when the topic is empty; 0 returns at once and spins
    POLL_MS to 500
  )
}

@Factory
class OrdersFactory {

  @Singleton
  fun faults(): Faults = Faults(OrdersFaults.DEFAULTS)
}

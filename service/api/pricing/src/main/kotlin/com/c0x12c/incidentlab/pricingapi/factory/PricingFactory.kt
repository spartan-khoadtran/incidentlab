package com.c0x12c.incidentlab.pricingapi.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

object PricingFaults {
  const val PRICE_BASE_MS = "price_base_ms"
  const val SLOW_FRACTION = "slow_fraction"
  const val EXTRA_LATENCY_MS = "extra_latency_ms"
  const val ERROR_RATE = "error_rate"
  const val RECOMMEND_MS = "recommend_ms"
  const val CPU_MS = "cpu_ms"

  val DEFAULTS: Map<String, Any?> = mapOf(
    // normal latency of /price
    PRICE_BASE_MS to 12,
    // share of /price requests that get extra latency
    SLOW_FRACTION to 0.0,
    EXTRA_LATENCY_MS to 0,
    // share of /price requests that fail with 503
    ERROR_RATE to 0.0,
    // normal latency of /recommend
    RECOMMEND_MS to 230,
    // CPU work per request
    CPU_MS to 1.0
  )
}

@Factory
class PricingFactory {

  @Singleton
  fun faults(): Faults = Faults(PricingFaults.DEFAULTS)
}

package com.c0x12c.incidentlab.loadgen.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.time.Duration

object LoadgenFaults {
  const val TENANTS = "tenants"
  const val RATE_MULTIPLIER = "rate_multiplier"
  const val SURGE_MULTIPLIER = "surge_multiplier"
  const val TENANT_OUTAGE = "tenant_outage"
  const val EXTRA_RPS = "extra_rps"
  const val CHECKOUT_SHARE = "checkout_share"
  const val TIMEOUT_MS = "timeout_ms"
  const val CHECKOUT_TIMEOUT_MS = "checkout_timeout_ms"
  const val RETRIES = "retries"

  val DEFAULTS: Map<String, Any?> = mapOf(
    // requests per second per tenant
    TENANTS to mapOf("web" to 20, "ios" to 15, "android" to 12, "partner-x" to 3),
    RATE_MULTIPLIER to 1.0,
    // stacked on rate_multiplier, so the learner's `make load` setting survives a surge exercise
    SURGE_MULTIPLIER to 1.0,
    // this tenant sends nothing
    TENANT_OUTAGE to null,
    // extra traffic per tenant: a crawler walking the whole catalog
    EXTRA_RPS to emptyMap<String, Any>(),
    CHECKOUT_SHARE to 0.3,
    TIMEOUT_MS to 3_000,
    // overrides timeout_ms for /checkout
    CHECKOUT_TIMEOUT_MS to null,
    // immediate retries on 5xx / timeout / connection error
    RETRIES to 0
  )
}

// Typed binding for app.loadgen.*: where the system under test answers, and how long to wait
// before the first arrival so the stack has settled.
@ConfigurationProperties("app.loadgen")
data class LoadgenConfig
  @ConfigurationInject
  constructor(
    val targetUrl: String = "http://envoy:8080",
    val startDelay: Duration = DEFAULT_START_DELAY
  ) {
    companion object {
      val DEFAULT_START_DELAY: Duration = Duration.ofSeconds(5)
    }
  }

@Factory
class LoadgenFactory {

  @Singleton
  fun faults(): Faults = Faults(LoadgenFaults.DEFAULTS)
}

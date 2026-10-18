package com.c0x12c.incidentlab.checkout.impl.config

import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties

// Typed binding for app.checkout.*: where pricing and Redis live.
@ConfigurationProperties("app.checkout")
data class CheckoutConfig
  @ConfigurationInject
  constructor(
    val pricingUrl: String = "http://pricing:8001",
    val redisHost: String = "redis",
    val redisPort: Int = DEFAULT_REDIS_PORT
  ) {
    companion object {
      const val DEFAULT_REDIS_PORT = 6379
    }
  }

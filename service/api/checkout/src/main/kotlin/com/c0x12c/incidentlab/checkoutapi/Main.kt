package com.c0x12c.incidentlab.checkoutapi

import io.micronaut.runtime.Micronaut

// checkout-api: the service users hit through the Envoy load balancer. Products from Redis or
// Postgres, checkout through Postgres, pricing and Kafka, and /health for Envoy's active checks.
object Main {
  @JvmStatic
  fun main(args: Array<String>) {
    Micronaut.build()
      .args(*args)
      .packages("com.c0x12c.incidentlab")
      .eagerInitSingletons(true)
      .start()
  }
}

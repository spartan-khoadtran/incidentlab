package com.c0x12c.incidentlab.loadgen

import io.micronaut.runtime.Micronaut

// loadgen: the users. Poisson arrivals per tenant against Envoy, with the client-side RED metrics.
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

package com.c0x12c.incidentlab.pricingapi

import io.micronaut.runtime.Micronaut

// pricing-api: the downstream service checkout calls for a quote, and for recommendations on v2.31.
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

package com.c0x12c.incidentlab.worker.orders

import io.micronaut.runtime.Micronaut

// orders-worker: consumes the order events the api produces, one sequential worker per partition.
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

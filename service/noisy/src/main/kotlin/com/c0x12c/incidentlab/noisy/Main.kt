package com.c0x12c.incidentlab.noisy

import io.micronaut.runtime.Micronaut

// noisy: the neighbour batch job. Burns CPU on demand so every other container competes for the host.
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

package com.c0x12c.incidentlab.pgobserver

import io.micronaut.runtime.Micronaut

// pgobserver: Postgres seen from the inside (the statistics views as Prometheus series) and the lab's fault agent for Postgres.
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

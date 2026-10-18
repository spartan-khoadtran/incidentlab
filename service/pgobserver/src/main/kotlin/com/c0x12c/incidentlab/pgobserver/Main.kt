package com.c0x12c.incidentlab.pgobserver

import io.micronaut.runtime.Micronaut

// pgobserver: Postgres seen from the inside. pg_stat_statements and pg_stat_activity as Prometheus series.
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

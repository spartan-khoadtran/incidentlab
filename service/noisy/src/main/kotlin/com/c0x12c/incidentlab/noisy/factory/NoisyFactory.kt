package com.c0x12c.incidentlab.noisy.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

object NoisyFaults {
  const val BURNERS = "burners"
  const val FORK_RPS = "fork_rps"

  val DEFAULTS: Map<String, Any?> = mapOf(
    BURNERS to 0,
    // short-lived processes started per second (capped at 200 inside ForkStorm)
    FORK_RPS to 0
  )
}

@Factory
class NoisyFactory {

  @Singleton
  fun faults(): Faults = Faults(NoisyFaults.DEFAULTS)
}

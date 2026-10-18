package com.c0x12c.incidentlab.noisy.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

object NoisyFaults {
  const val BURNERS = "burners"

  val DEFAULTS: Map<String, Any?> = mapOf(BURNERS to 0)
}

@Factory
class NoisyFactory {

  @Singleton
  fun faults(): Faults = Faults(NoisyFaults.DEFAULTS)
}

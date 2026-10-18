package com.c0x12c.incidentlab.noisy.factory

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

object NoisyFaults {
  const val BURNERS = "burners"
  const val FSYNC_MB_PER_S = "fsync_mb_per_s"

  val DEFAULTS: Map<String, Any?> = mapOf(BURNERS to 0, FSYNC_MB_PER_S to 0)
}

@Factory
class NoisyFactory {

  @Singleton
  fun faults(): Faults = Faults(NoisyFaults.DEFAULTS)
}

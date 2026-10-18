package com.c0x12c.incidentlab.noisy.controller

import com.c0x12c.incidentlab.noisy.burner.BurnerPool
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

// The lab controller reads cpu_count here to size the noisy-neighbour exercise for this machine.
@Controller("/health")
class HealthController(
  private val burners: BurnerPool
) {

  @Get
  fun health(): Map<String, Int> = mapOf("burners" to burners.running, "cpu_count" to Runtime.getRuntime().availableProcessors())
}

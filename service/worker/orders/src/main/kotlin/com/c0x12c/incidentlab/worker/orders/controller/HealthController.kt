package com.c0x12c.incidentlab.worker.orders.controller

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

@Controller("/health")
class HealthController {

  @Get
  fun health(): Map<String, Boolean> = mapOf("ok" to true)
}

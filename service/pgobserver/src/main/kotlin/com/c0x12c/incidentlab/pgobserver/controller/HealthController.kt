package com.c0x12c.incidentlab.pgobserver.controller

import com.c0x12c.incidentlab.pgobserver.poller.PgStatsPoller
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

@Controller("/health")
class HealthController(
  private val poller: PgStatsPoller
) {

  @Get
  fun health(): Map<String, Boolean> = mapOf("ok" to poller.current.up)
}

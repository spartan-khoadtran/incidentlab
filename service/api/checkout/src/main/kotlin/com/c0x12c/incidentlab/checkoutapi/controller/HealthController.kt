package com.c0x12c.incidentlab.checkoutapi.controller

import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import kotlin.random.Random

// Envoy's active health check. The health_fail_prob fault makes it answer 503 at random, which is
// how the load balancer exercise takes the backend out of rotation without the api seeing an error.
@Controller("/health")
class HealthController(
  private val faults: Faults
) {

  @Get
  fun health(): HttpResponse<Map<String, Boolean>> =
    if (Random.nextDouble() < faults.double(HEALTH_FAIL_PROB)) {
      HttpResponse.status<Map<String, Boolean>>(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("ok" to false))
    } else {
      HttpResponse.ok(mapOf("ok" to true))
    }

  private companion object {
    const val HEALTH_FAIL_PROB = "health_fail_prob"
  }
}

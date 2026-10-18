package com.c0x12c.incidentlab.pricingapi.controller

import com.c0x12c.incidentlab.client.dto.pricing.PriceQuote
import com.c0x12c.incidentlab.client.dto.pricing.PriceRequest
import com.c0x12c.incidentlab.client.dto.pricing.RecommendRequest
import com.c0x12c.incidentlab.client.dto.pricing.Recommendation
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.infra.runtime.EventLoopRuntime
import com.c0x12c.incidentlab.pricingapi.factory.PricingFaults
import com.c0x12c.incidentlab.tracing.span
import com.c0x12c.incidentlab.utility.CpuBurner
import com.c0x12c.incidentlab.utility.jitter
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.opentelemetry.api.trace.Tracer
import kotlin.math.round
import kotlin.random.Random

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
class PricingController(
  private val faults: Faults,
  private val runtime: EventLoopRuntime,
  private val tracer: Tracer
) {

  @Post("/price")
  fun price(@Body body: PriceRequest): HttpResponse<*> {
    burn()
    var delay = jitter(faults.double(PricingFaults.PRICE_BASE_MS))
    if (Random.nextDouble() < faults.double(PricingFaults.SLOW_FRACTION)) {
      delay += jitter(faults.double(PricingFaults.EXTRA_LATENCY_MS), EXTRA_LATENCY_JITTER)
    }
    tracer.span("rules.evaluate") { Thread.sleep(delay.toMillis()) }
    if (Random.nextDouble() < faults.double(PricingFaults.ERROR_RATE)) {
      return HttpResponse.status<Map<String, String>>(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "rules engine unavailable"))
    }
    val total = round(body.prices.values.sum() * (1 - DISCOUNT) * CENTS) / CENTS
    return HttpResponse.ok(PriceQuote(total = total, discount = DISCOUNT))
  }

  @Post("/recommend")
  fun recommend(@Body body: RecommendRequest): Recommendation {
    burn()
    tracer.span("model.score") {
      Thread.sleep(jitter(faults.double(PricingFaults.RECOMMEND_MS), RECOMMEND_JITTER).toMillis())
    }
    return Recommendation(items = (1..CATALOG_TAIL).shuffled().take(RECOMMENDATIONS))
  }

  private fun burn() {
    val millis = faults.double(PricingFaults.CPU_MS)
    if (millis > 0) {
      runtime.onEventLoop { CpuBurner.burn(jitter(millis, 0.0)) }
    }
  }

  private companion object {
    const val DISCOUNT = 0.05
    const val CENTS = 100.0
    const val EXTRA_LATENCY_JITTER = 0.3
    const val RECOMMEND_JITTER = 0.15
    const val CATALOG_TAIL = 500
    const val RECOMMENDATIONS = 3
  }
}

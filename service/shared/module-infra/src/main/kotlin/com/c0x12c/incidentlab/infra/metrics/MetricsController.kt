package com.c0x12c.incidentlab.infra.metrics

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header

@Controller("/metrics")
class MetricsController(
  private val registry: PrometheusMeterRegistry
) {

  // Exemplars only exist in OpenMetrics, so answer in that format when the scraper accepts it.
  @Get
  fun scrape(@Header(HttpHeaders.ACCEPT) accept: String?): HttpResponse<String> {
    val contentType = if (accept?.contains(OPENMETRICS) == true) OPENMETRICS_TEXT else PROMETHEUS_TEXT
    return HttpResponse.ok(registry.scrape(contentType)).contentType(contentType)
  }

  companion object {
    private const val OPENMETRICS = "application/openmetrics-text"
    const val OPENMETRICS_TEXT = "$OPENMETRICS; version=1.0.0; charset=utf-8"
    const val PROMETHEUS_TEXT = "text/plain; version=0.0.4; charset=utf-8"
  }
}

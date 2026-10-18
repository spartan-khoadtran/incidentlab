package com.c0x12c.incidentlab.infra.config

import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties

// Typed binding for app.service.*. name is the Prometheus job and the OpenTelemetry service name.
@ConfigurationProperties("app.service")
data class AppServiceConfig
  @ConfigurationInject
  constructor(
    val name: String = "unknown"
  )

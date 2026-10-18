package com.c0x12c.incidentlab.noisy.config

import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties

// Typed binding for app.disk.*. dir sits on a Docker volume, so it shares the disk with pgdata.
@ConfigurationProperties("app.disk")
data class NoisyDiskConfig
  @ConfigurationInject
  constructor(
    val dir: String = "/data"
  )

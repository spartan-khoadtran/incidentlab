package com.c0x12c.incidentlab.infra.config

import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties

// Typed binding for app.kafka.*, shared by the producer side (api) and the consumer side (worker).
@ConfigurationProperties("app.kafka")
data class AppKafkaConfig
  @ConfigurationInject
  constructor(
    val bootstrap: String = "kafka:9092",
    val topic: String = "orders",
    val partitions: Int = DEFAULT_PARTITIONS
  ) {
    companion object {
      const val DEFAULT_PARTITIONS = 3
    }
  }

package com.c0x12c.incidentlab.database

import java.time.Duration

data class DatabaseConfig(
  val jdbcUrl: String,
  val username: String,
  val password: String,
  val applicationName: String,
  val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
  val minimumIdle: Int = DEFAULT_MINIMUM_IDLE,
  val connectionTimeout: Duration = DEFAULT_CONNECTION_TIMEOUT
) {
  companion object {
    const val DEFAULT_MAX_POOL_SIZE = 24
    const val DEFAULT_MINIMUM_IDLE = 2
    val DEFAULT_CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(30)
  }
}

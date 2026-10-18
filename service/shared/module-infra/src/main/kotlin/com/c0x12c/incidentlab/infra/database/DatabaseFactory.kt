package com.c0x12c.incidentlab.infra.database

import com.c0x12c.logging.Logging
import com.c0x12c.incidentlab.database.DatabaseConfig
import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.infra.config.AppServiceConfig
import com.c0x12c.incidentlab.utility.retryForever
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

// Every bean here is gated on app.database.url: a deployable that configures no database gets no
// pool, which is what makes a downstream `@Requires(beans = [DatabaseContext])` mean what it says.
private const val DATABASE_URL = "app.database.url"

@ConfigurationProperties("app.database")
@Requires(property = DATABASE_URL)
data class AppDatabaseConfig
  @ConfigurationInject
  constructor(
    val url: String,
    val username: String = "lab",
    val password: String = "lab",
    val poolMax: Int = DatabaseConfig.DEFAULT_MAX_POOL_SIZE
  )

@Factory
class DatabaseFactory : Logging {

  // The pool is built at boot and waits for Postgres: under compose the database may still be
  // loading its seed data when this service starts, and a service that dies on that would restart
  // in a loop instead of simply being a little late.
  @Singleton
  @Bean(preDestroy = "close")
  @Requires(property = DATABASE_URL)
  fun databaseContext(config: AppDatabaseConfig, service: AppServiceConfig): DatabaseContext =
    retryForever("postgres") {
      DatabaseContext(
        DatabaseConfig(
          jdbcUrl = config.url,
          username = config.username,
          password = config.password,
          applicationName = service.name,
          maxPoolSize = config.poolMax
        )
      ).also { context -> context.withConnection { it.createStatement().use { statement -> statement.execute("SELECT 1") } } }
    }
}

package com.c0x12c.incidentlab.database

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

/**
 * One Hikari pool. The lab's pool size fault is NOT this pool: the api gates its own borrowers in
 * front of it with a resizable gate, so the Hikari limit only has to stay above the largest gate.
 */
class DatabaseContext(val config: DatabaseConfig) : AutoCloseable, Logging {

  val dataSource: HikariDataSource = HikariDataSource(
    HikariConfig().apply {
      jdbcUrl = config.jdbcUrl
      username = config.username
      password = config.password
      maximumPoolSize = config.maxPoolSize
      minimumIdle = config.minimumIdle
      connectionTimeout = config.connectionTimeout.toMillis()
      poolName = "hikari-${config.applicationName}"
      addDataSourceProperty("ApplicationName", config.applicationName)
    }
  ).also { info { "database pool ready [url=${config.jdbcUrl}, max=${config.maxPoolSize}]" } }

  fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

  override fun close() {
    dataSource.close()
  }
}

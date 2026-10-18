package com.c0x12c.incidentlab.database

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

fun <T> Connection.query(sql: String, bind: (PreparedStatement) -> Unit = {}, read: (ResultSet) -> T): T =
  prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use(read)
  }

fun Connection.execute(sql: String, bind: (PreparedStatement) -> Unit = {}): Int =
  prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeUpdate()
  }

fun <T> ResultSet.rows(row: (ResultSet) -> T): List<T> {
  val out = mutableListOf<T>()
  while (next()) {
    out.add(row(this))
  }
  return out
}

fun <T> ResultSet.firstRow(row: (ResultSet) -> T): T? = if (next()) row(this) else null

fun Connection.intArray(values: Collection<Int>): java.sql.Array = createArrayOf("integer", values.toTypedArray())

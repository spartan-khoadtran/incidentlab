package com.c0x12c.incidentlab.checkout.api

import java.sql.Connection

interface OrderRepository {

  fun insertOrder(connection: Connection, tenant: String, total: Double): Long

  fun insertItems(connection: Connection, orderId: Long, items: List<Int>)

  /** Decrements the one inventory row every checkout shares when the hot-row fault is on. */
  fun reserveInventory(connection: Connection)

  fun markProcessed(connection: Connection, orderId: Long): Int
}

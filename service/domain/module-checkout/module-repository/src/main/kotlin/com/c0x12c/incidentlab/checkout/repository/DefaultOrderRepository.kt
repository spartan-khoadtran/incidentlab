package com.c0x12c.incidentlab.checkout.repository

import com.c0x12c.incidentlab.checkout.api.OrderRepository
import com.c0x12c.incidentlab.database.execute
import com.c0x12c.incidentlab.database.firstRow
import com.c0x12c.incidentlab.database.intArray
import com.c0x12c.incidentlab.database.query
import java.sql.Connection

class DefaultOrderRepository(
  private val instrumentation: QueryInstrumentation
) : OrderRepository {

  override fun insertOrder(connection: Connection, tenant: String, total: Double): Long =
    instrumentation.timed("insert_order") {
      connection.query(INSERT_ORDER, { statement ->
        statement.setString(1, tenant)
        statement.setDouble(2, total)
      }) { rows -> rows.firstRow { it.getLong(1) } ?: error("INSERT ... RETURNING id returned no row") }
    }

  override fun insertItems(connection: Connection, orderId: Long, items: List<Int>) {
    instrumentation.timed("insert_items") {
      connection.execute(INSERT_ITEMS) { statement ->
        statement.setLong(1, orderId)
        statement.setArray(2, connection.intArray(items))
      }
    }
  }

  override fun reserveInventory(connection: Connection) {
    instrumentation.timed("update_inventory") {
      connection.execute(UPDATE_INVENTORY)
    }
  }

  override fun markProcessed(connection: Connection, orderId: Long): Int =
    instrumentation.timed("mark_processed") {
      connection.execute(MARK_PROCESSED) { it.setLong(1, orderId) }
    }

  companion object {
    const val INSERT_ORDER = "/* q=insert_order */ INSERT INTO orders (tenant, total, status) VALUES (?, ?, 'new') RETURNING id"
    const val INSERT_ITEMS = "/* q=insert_items */ INSERT INTO order_items (order_id, product_id, qty) SELECT ?, unnest(?::int[]), 1"
    const val UPDATE_INVENTORY = "/* q=update_inventory */ UPDATE inventory SET stock = stock - 1 WHERE sku = 'HOT-1'"
    const val MARK_PROCESSED = "/* q=mark_processed */ UPDATE orders SET status = 'processed' WHERE id = ?"
  }
}

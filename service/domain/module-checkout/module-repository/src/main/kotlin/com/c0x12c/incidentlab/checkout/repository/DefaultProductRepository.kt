package com.c0x12c.incidentlab.checkout.repository

import com.c0x12c.incidentlab.checkout.api.ProductRepository
import com.c0x12c.incidentlab.client.dto.checkout.ProductDetail
import com.c0x12c.incidentlab.database.firstRow
import com.c0x12c.incidentlab.database.intArray
import com.c0x12c.incidentlab.database.query
import com.c0x12c.incidentlab.database.rows
import java.sql.Connection

class DefaultProductRepository(
  private val instrumentation: QueryInstrumentation
) : ProductRepository {

  override fun productDetail(connection: Connection, id: Int): ProductDetail? =
    instrumentation.timed("product_detail") {
      connection.query(PRODUCT_DETAIL, { it.setInt(1, id) }) { rows ->
        rows.firstRow {
          ProductDetail(
            id = it.getInt("id"),
            name = it.getString("name"),
            price = it.getDouble("price"),
            reviews = it.getLong("reviews"),
            rating = it.getDouble("rating")
          )
        }
      }
    }

  override fun prices(connection: Connection, ids: List<Int>): Map<Int, Double> =
    instrumentation.timed("product_prices") {
      connection.query(PRODUCT_PRICES, { it.setArray(1, connection.intArray(ids)) }) { rows ->
        rows.rows { it.getInt("id") to it.getDouble("price") }.toMap()
      }
    }

  override fun priceById(connection: Connection, id: Int): Pair<Int, Double>? =
    instrumentation.timed("product_by_id") {
      connection.query(PRODUCT_BY_ID, { it.setInt(1, id) }) { rows ->
        rows.firstRow { it.getInt("id") to it.getDouble("price") }
      }
    }

  companion object {
    const val PRODUCT_DETAIL = "/* q=product_detail */ SELECT p.id, p.name, p.price, count(r.id) AS reviews, " +
      "coalesce(avg(r.rating), 0)::float AS rating FROM products p " +
      "LEFT JOIN reviews r ON r.product_id = p.id WHERE p.id = ? GROUP BY p.id"
    const val PRODUCT_PRICES = "/* q=product_prices */ SELECT id, price::float AS price FROM products WHERE id = ANY(?)"
    const val PRODUCT_BY_ID = "/* q=product_by_id */ SELECT id, price::float AS price, category FROM products WHERE id = ?"
  }
}

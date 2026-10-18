package com.c0x12c.incidentlab.checkout.api

import com.c0x12c.incidentlab.client.dto.checkout.ProductDetail
import java.sql.Connection

// Every method takes the connection it runs on: the manager owns the session, because how long a
// connection is held (and what else happens while it is) is the subject of two exercises.
interface ProductRepository {

  fun productDetail(connection: Connection, id: Int): ProductDetail?

  fun prices(connection: Connection, ids: List<Int>): Map<Int, Double>

  fun priceById(connection: Connection, id: Int): Pair<Int, Double>?
}

package com.c0x12c.incidentlab.client.dto.checkout

data class ProductDetail(
  val id: Int,
  val name: String,
  val price: Double,
  val reviews: Long,
  val rating: Double
)

data class CheckoutRequest(
  val items: List<Int>? = null
)

data class CheckoutReceipt(
  val orderId: Long,
  val total: Double
)

/** The order event the api produces to Kafka and the worker consumes. createdMs is epoch millis. */
data class OrderEvent(
  val orderId: Long,
  val tenant: String,
  val total: Double,
  val createdMs: Long,
  val source: String,
  val currency: String? = null,
  val poison: String? = null
)

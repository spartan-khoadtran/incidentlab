package com.c0x12c.incidentlab.shared.exception

import io.micronaut.http.HttpStatus

// The checkout api's error catalog. The two 5xx are the ones the exercises provoke on purpose: a
// pool that cannot hand out a connection in time, and a pricing call that failed or timed out.
enum class CheckoutError(
  val code: String,
  val message: String,
  val status: HttpStatus
) {
  PRODUCT_NOT_FOUND("not_found", "not found", HttpStatus.NOT_FOUND),
  POOL_TIMEOUT("db_pool_timeout", "db pool timeout", HttpStatus.SERVICE_UNAVAILABLE),
  PRICING_FAILED("pricing_failed", "pricing call failed", HttpStatus.BAD_GATEWAY);

  fun asException(detail: String? = null): ClientException = ClientException(code, detail ?: message, status)
}

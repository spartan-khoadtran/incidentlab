package com.c0x12c.incidentlab.checkout.api

import arrow.core.Either
import com.c0x12c.incidentlab.client.dto.checkout.CheckoutReceipt
import com.c0x12c.incidentlab.client.dto.checkout.ProductDetail
import com.c0x12c.incidentlab.shared.exception.ClientException

/**
 * The checkout service as users see it through the load balancer. Left carries the two failures
 * the exercises provoke: the pool that cannot hand out a connection in time (503) and the pricing
 * call that failed (502). Everything else is a Right or a bug.
 */
interface CheckoutManager {

  /** The product page: Redis first, Postgres on a miss. */
  fun product(id: Int): Either<ClientException, ProductDetail>

  /** Prices from Postgres, a quote from pricing, the order written, one event to Kafka. */
  fun checkout(tenant: String, items: List<Int>): Either<ClientException, CheckoutReceipt>
}

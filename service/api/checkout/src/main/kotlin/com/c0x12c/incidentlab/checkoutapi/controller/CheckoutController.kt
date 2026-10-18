package com.c0x12c.incidentlab.checkoutapi.controller

import com.c0x12c.incidentlab.checkout.api.CheckoutManager
import com.c0x12c.incidentlab.client.dto.checkout.CheckoutReceipt
import com.c0x12c.incidentlab.client.dto.checkout.CheckoutRequest
import com.c0x12c.incidentlab.shared.exception.throwOrValue
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

@Controller("/checkout")
@ExecuteOn(TaskExecutors.BLOCKING)
class CheckoutController(
  private val manager: CheckoutManager
) {

  @Post
  fun checkout(
    @Nullable @Header("X-Tenant") tenant: String?,
    @Nullable @Body body: CheckoutRequest?
  ): CheckoutReceipt = manager.checkout(tenant ?: "unknown", body?.items.orEmpty()).throwOrValue()
}

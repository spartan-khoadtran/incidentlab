package com.c0x12c.incidentlab.checkoutapi.controller

import com.c0x12c.incidentlab.checkout.api.CheckoutManager
import com.c0x12c.incidentlab.client.dto.checkout.ProductDetail
import com.c0x12c.incidentlab.shared.exception.throwOrValue
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

@Controller("/products")
@ExecuteOn(TaskExecutors.BLOCKING)
class ProductController(
  private val manager: CheckoutManager
) {

  @Get("/{id}")
  fun product(id: Int): ProductDetail = manager.product(id).throwOrValue()
}

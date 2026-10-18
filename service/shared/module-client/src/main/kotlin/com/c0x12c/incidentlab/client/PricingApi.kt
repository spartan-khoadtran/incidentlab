package com.c0x12c.incidentlab.client

import com.c0x12c.incidentlab.client.dto.pricing.PriceQuote
import com.c0x12c.incidentlab.client.dto.pricing.PriceRequest
import com.c0x12c.incidentlab.client.dto.pricing.RecommendRequest
import com.c0x12c.incidentlab.client.dto.pricing.Recommendation
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface PricingApi {

  @POST("/price")
  fun price(@Body body: PriceRequest): Call<PriceQuote>

  @POST("/recommend")
  fun recommend(@Body body: RecommendRequest): Call<Recommendation>
}

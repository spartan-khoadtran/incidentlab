package com.c0x12c.incidentlab.client.dto.pricing

data class PriceRequest(
  val items: List<Int>,
  val prices: Map<Int, Double>
)

data class PriceQuote(
  val total: Double,
  val discount: Double
)

data class RecommendRequest(
  val items: List<Int>
)

data class Recommendation(
  val items: List<Int>
)

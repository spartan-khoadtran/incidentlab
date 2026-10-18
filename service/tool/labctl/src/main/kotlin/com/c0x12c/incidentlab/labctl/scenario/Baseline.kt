package com.c0x12c.incidentlab.labctl.scenario

import kotlin.math.roundToInt

/** The healthy lab in numbers: the tenant mix, what a checkout costs, the worker's capacity, and the unit constants. */
object Baseline {

  val BASE_TENANTS: Map<String, Int> = mapOf("web" to 20, "ios" to 15, "android" to 12, "partner-x" to 3)
  val BASE_RPS: Int = BASE_TENANTS.values.sum()
  const val BASE_CHECKOUT_SHARE = 0.3
  val CHECKOUTS_PER_SECOND: Int = (BASE_RPS * BASE_CHECKOUT_SHARE).roundToInt()
  val PRODUCTS_PER_SECOND: Int = BASE_RPS - CHECKOUTS_PER_SECOND
  val BASE_CONNECTIONS_IN_USE: Double = CHECKOUTS_PER_SECOND * BASE_CHECKOUT_HOLD_MS / MILLIS_PER_SECOND

  const val AVERAGE_BASKET = 2.5
  const val BASE_CHECKOUT_HOLD_MS = 50
  const val PRICE_BASE_MS = 12
  const val PARTITIONS = 3
  const val WORKER_CAPACITY_PER_SECOND = 120

  // Measured: 25 ms of work plus about 1.5 ms of overhead per message.
  const val MEASURED_WORKER_CAPACITY = 113

  // Measured: what api burns at BASE_RPS with 2 cores available.
  const val API_CORES_AT_BASE = 0.06

  const val DEFAULT_CPU_COUNT = 4
  const val DEFAULT_PRODUCT_DETAIL_SECONDS = 0.012

  const val PERCENT = 100
  const val HUNDREDTHS = 100.0
  const val THOUSANDTHS = 1_000.0
  const val MILLIS_PER_SECOND = 1_000.0
  const val SECONDS_PER_MINUTE = 60
  const val SECONDS_PER_HOUR = 3_600

  fun round2(value: Double): Double = (value * HUNDREDTHS).roundToInt() / HUNDREDTHS
}

package com.c0x12c.incidentlab.checkout.impl

// Every fault key the api reads, with the value a healthy system runs on. The controller only ever
// sends a subset of these as overrides; a key missing from a scenario means "normal".
object CheckoutFaults {
  const val ADMISSION_LIMIT = "admission_limit"
  const val POOL_MAX = "pool_max"
  const val POOL_ACQUIRE_TIMEOUT_S = "pool_acquire_timeout_s"
  const val CACHE_TTL_MS = "cache_ttl_ms"
  const val CPU_BURN_MS = "cpu_burn_ms"
  const val PRICE_WORK_MS = "price_work_ms"
  const val N_PLUS_ONE = "n_plus_one"
  const val RECOMMEND_IN_TXN = "recommend_in_txn"
  const val HOT_ROW = "hot_row"
  const val LOCK_HOLD_MS = "lock_hold_ms"
  const val LEAK_KB = "leak_kb"
  const val HEALTH_FAIL_PROB = "health_fail_prob"
  const val EXTRA_EVENTS_RPS = "extra_events_rps"
  const val POISON_NONCE = "poison_nonce"
  const val PRICING_TIMEOUT_S = "pricing_timeout_s"
  const val RETAIN_MB = "retain_mb"
  const val CHURN_KB = "churn_kb"
  const val PIN_DB = "pin_db"
  const val PIN_HOLD_MS = "pin_hold_ms"
  const val DEBUG_LOG_LINES = "debug_log_lines"

  val DEFAULTS: Map<String, Any?> = mapOf(
    // max concurrent requests the app accepts; the rest wait before the handler starts timing
    ADMISSION_LIMIT to 256,
    // DB connection pool size as the app sees it (the gate in front of the real pool)
    POOL_MAX to 4,
    POOL_ACQUIRE_TIMEOUT_S to 2.0,
    CACHE_TTL_MS to 120_000,
    // app work per checkout after loading prices, with the connection still held (outside any transaction)
    PRICE_WORK_MS to 45,
    // CPU work on the event loop per /products request
    CPU_BURN_MS to 0,
    // >0: load prices with one query per item, repeated k times
    N_PLUS_ONE to 0,
    // the v2.31 feature: call /recommend while holding a DB connection and an open transaction
    RECOMMEND_IN_TXN to false,
    // every checkout updates the same inventory row
    HOT_ROW to false,
    // extra time spent inside the transaction after taking the row lock
    LOCK_HOLD_MS to 0,
    // memory kept for ever per request
    LEAK_KB to 0,
    // probability that /health answers 503
    HEALTH_FAIL_PROB to 0.0,
    // synthetic backfill events pushed to Kafka
    EXTRA_EVENTS_RPS to 0,
    // produce one malformed event when this changes
    POISON_NONCE to null,
    PRICING_TIMEOUT_S to 2.0,
    // live data the GC-pressure store keeps on the heap
    RETAIN_MB to 0,
    // new objects per request written over random old ones in that store
    CHURN_KB to 0,
    // checkout DB work inside synchronized, which pins a virtual thread to its carrier
    PIN_DB to false,
    // blocking cache put inside that synchronized block
    PIN_HOLD_MS to 0,
    // DEBUG lines per request that reach the console
    DEBUG_LOG_LINES to 0
  )
}

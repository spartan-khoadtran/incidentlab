package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

object AppBoard {

  fun build(): Board {
    val b = Board("use-app", "02 · USE · App resources", "Pools, the admission gate and the lock inside api, and the queue in the Kafka consumer.")
    b.useIntro()

    b.row("Software resources inside api")
    b.timeseries(
      "DB pool: in use, max, waiting",
      listOf("db_pool_in_use{job=\"api\"}" to "in use", "db_pool_max{job=\"api\"}" to "max", "db_pool_waiting{job=\"api\"}" to "waiting (saturation)"),
      "short"
    )
    b.timeseries("Wait for a connection p99", listOf(quantile(0.99, "db_pool_acquire_seconds", selector = "{job=\"api\"}") to "acquire wait p99"), "s")
    b.timeseries("Pool timeouts/s (errors)", listOf("rate(db_pool_timeouts_total{job=\"api\"}[RI])" to "timeouts/s"), "short")
    b.timeseries(
      "Admission: in handler and waiting",
      listOf("app_admission_inflight{job=\"api\"}" to "in flight", "app_admission_waiting{job=\"api\"}" to "waiting (saturation)"),
      "short",
      "Requests waiting here are not yet counted in api's duration.",
      width = 10
    )
    b.stat("Admission limit", "app_admission_limit{job=\"api\"}", width = 4)
    b.timeseries("Admission wait p99", listOf(quantile(0.99, "app_admission_wait_seconds", selector = "{job=\"api\"}") to "wait p99"), "s", width = 10)
    b.timeseries(
      "Inventory lock: wait p99",
      listOf(quantile(0.99, "app_lock_wait_seconds", selector = "{job=\"api\"}") to "lock wait p99"),
      "s",
      "Time a checkout waits for api's one inventory lock. Off-CPU time: the thread uses no CPU while it waits."
    )
    b.timeseries("Inventory lock: threads waiting (saturation)", listOf("app_lock_waiting{job=\"api\"}" to "waiting"), "short")
    b.timeseries(
      "Inventory lock: utilization",
      listOf("100 * rate(app_lock_held_seconds_total{job=\"api\"}[RI])" to "held %"),
      "percent",
      "Share of time the lock is held. By M/M/1 the mean wait is utilization / (1 - utilization) times the hold time."
    )

    b.row("Kafka consumer (worker)")
    b.timeseries("Lag (seconds) by partition", listOf("kafka_consumer_lag_seconds" to "p{{partition}}"), "s")
    b.timeseries("Messages waiting in worker memory", listOf("kafka_partition_queue" to "p{{partition}}"), "short")
    b.timeseries("Processing errors/s", listOf("sum by (source) (rate(kafka_consumed_total{outcome=\"error\"}[RI])) or vector(0)" to "{{source}}"), "short")
    return b
  }
}

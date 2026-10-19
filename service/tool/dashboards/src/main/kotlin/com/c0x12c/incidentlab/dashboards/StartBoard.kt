package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

/** One screen across every layer, each panel linking to the board with the detail. No thresholds: the learner decides what moved. */
object StartBoard {

  private const val LB = "envoy_http_conn_manager_prefix=\"ingress\""
  private const val WIDTH = 6
  private const val HEIGHT = 7

  fun build(red: Board, app: Board, runtime: Board, host: Board, data: Board): Board {
    val b = Board("start", "00 · Start here", "Every layer on one screen. Find the first layer that moved, then open its board.")
    b.text(
      "**Find the first layer that moved, then open its board.** Each panel links to its detail board (the link icon in its corner), " +
        "and the Lab menu at the top lists every board. The time range follows you. Compare with the minutes before the ticket.",
      height = 2
    )

    b.row("Users and the edge")
    b.timeseries("User requests/s by outcome", listOf("sum by (outcome) (rate(client_requests_total[RI]))" to "{{outcome}}"), "reqps", width = WIDTH, height = HEIGHT, linkTo = red)
    b.timeseries(
      "Error rate users see",
      listOf("100 * (sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[RI])) or vector(0)) / sum(rate(client_requests_total[RI]))" to "error %"),
      "percent",
      width = WIDTH,
      height = HEIGHT,
      linkTo = red
    )
    b.timeseries(
      "p99 at each layer: client, LB, api",
      listOf(
        quantile(0.99, "client_duration_seconds") to "client",
        quantile(0.99, "envoy_http_downstream_rq_time", selector = "{$LB}") + " / 1000" to "LB (Envoy)",
        quantile(0.99, "http_server_duration_seconds", selector = "{job=\"api\"}") to "api"
      ),
      "s",
      "The gap between two lines is time spent between those two layers.",
      width = WIDTH,
      height = HEIGHT,
      linkTo = red
    )
    b.timeseries(
      "5xx: LB against api",
      listOf(
        "sum(rate(envoy_http_downstream_rq_xx{$LB,envoy_response_code_class=\"5\"}[RI])) or vector(0)" to "LB returns",
        "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[RI])) or vector(0)" to "api records"
      ),
      "reqps",
      width = WIDTH,
      height = HEIGHT,
      linkTo = red
    )

    b.row("Downstream and async")
    b.timeseries(
      "p99 of api's calls: Postgres session and pricing",
      listOf(
        quantile(0.99, "db_client_session_seconds", selector = "{job=\"api\"}") to "DB session (wait + hold)",
        quantile(0.99, "http_client_duration_seconds", "route", "{job=\"api\"}") to "pricing {{route}}"
      ),
      "s",
      width = WIDTH,
      height = HEIGHT,
      linkTo = red
    )
    b.timeseries("Cache hit ratio", listOf("sum(rate(cache_requests_total{result=\"hit\"}[RI])) / sum(rate(cache_requests_total[RI]))" to "hit ratio"), "percentunit", width = WIDTH, height = HEIGHT, linkTo = red)
    b.timeseries(
      "Kafka: produce/s against consume/s",
      listOf("sum(rate(kafka_produced_total{outcome=\"ok\"}[RI]))" to "produce", "sum(rate(kafka_consumed_total{outcome=\"ok\"}[RI]))" to "consume ok"),
      "reqps",
      width = WIDTH,
      height = HEIGHT,
      linkTo = red
    )
    b.timeseries("Kafka: largest lag", listOf("max(kafka_consumer_lag_seconds)" to "lag"), "s", width = WIDTH, height = HEIGHT, linkTo = red)

    b.row("Saturation: one panel per resource")
    b.timeseries(
      "Waiting inside api: pool, admission, lock",
      listOf("db_pool_waiting{job=\"api\"}" to "DB pool", "app_admission_waiting{job=\"api\"}" to "admission", "app_lock_waiting{job=\"api\"}" to "inventory lock"),
      "short",
      width = WIDTH,
      height = HEIGHT,
      linkTo = app
    )
    b.timeseries(
      "Stalls: event loop lag and GC pause p99",
      listOf(quantile(0.99, "event_loop_lag_seconds", "job") to "{{job}} event loop", quantile(0.99, "jvm_gc_pause_seconds", "job") to "{{job}} GC pause"),
      "s",
      width = WIDTH,
      height = HEIGHT,
      linkTo = runtime
    )
    b.timeseries(
      "api: CPU usage against its limit",
      listOf("rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[RI])" to "usage", "cgroup_cpu_limit_cores{job=\"api\"}" to "limit"),
      "short",
      width = WIDTH,
      height = HEIGHT,
      linkTo = host
    )
    b.timeseries(
      "Share of throttled periods",
      listOf("100 * rate(cgroup_cpu_throttled_periods_total[RI]) / rate(cgroup_cpu_periods_total[RI])" to "{{job}}"),
      "percent",
      width = WIDTH,
      height = HEIGHT,
      linkTo = host
    )
    b.timeseries(
      "Host: load against CPU count",
      listOf("node_load1" to "load1", "count(node_cpu_seconds_total{mode=\"idle\"})" to "CPU count"),
      "short",
      width = WIDTH,
      height = HEIGHT,
      linkTo = host
    )
    b.timeseries("TCP retransmits/s", listOf("rate(net_tcp_retrans_segs_total[RI])" to "{{job}}"), "short", width = WIDTH, height = HEIGHT, linkTo = host)
    b.timeseries(
      "Postgres: backends waiting",
      listOf("pg_lock_waiters" to "on a lock", "sum(pg_wait_events{type=\"IO\"}) or vector(0)" to "on IO"),
      "short",
      width = WIDTH,
      height = HEIGHT,
      linkTo = data
    )
    b.timeseries("Disk queue (mean I/Os in flight)", listOf("rate(node_disk_io_time_weighted_seconds_total[RI])" to "{{device}}"), "short", width = WIDTH, height = HEIGHT, linkTo = data)
    return b
  }
}

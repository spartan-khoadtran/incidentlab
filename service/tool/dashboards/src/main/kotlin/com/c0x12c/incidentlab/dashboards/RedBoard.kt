package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

object RedBoard {

  private const val LB = "envoy_http_conn_manager_prefix=\"ingress\""

  fun build(): Board {
    val b = Board("red-layers", "01 · RED by layer", "Rate, Errors, Duration at each layer, following the request path: client -> LB -> api -> downstream -> async.")
    b.text(
      "**Read top to bottom along the request path.** The first layer whose numbers drift is where to dig. " +
        "The orange line on the charts is a change event (deploy, config). " +
        "Commands: `make new` · `make hint` · `make answer`",
      height = 2
    )

    b.row("1 · Client (loadgen): what users see")
    b.timeseries(
      "User requests/s by outcome",
      listOf("sum by (outcome) (rate(client_requests_total[RI]))" to "{{outcome}}"),
      "reqps",
      "Each logical user request, after retries. timeout and conn_error are errors only the client sees."
    )
    b.timeseries(
      "Error rate users see",
      listOf("100 * (sum(rate(client_requests_total{outcome!~\"ok|http_4xx\"}[RI])) or vector(0)) / sum(rate(client_requests_total[RI]))" to "error %"),
      "percent"
    )
    b.timeseries(
      "Latency users see",
      listOf(quantile(0.5, "client_duration_seconds") to "p50", quantile(0.95, "client_duration_seconds") to "p95", quantile(0.99, "client_duration_seconds") to "p99"),
      "s"
    )
    b.timeseries("Requests/s by tenant", listOf("sum by (tenant) (rate(client_requests_total[RI]))" to "{{tenant}}"), "reqps", "Workload characterization: who is causing the load?")
    b.timeseries(
      "Attempts vs user requests",
      listOf("sum(rate(client_attempts_total[RI]))" to "attempts sent", "sum(rate(client_requests_total[RI]))" to "user requests"),
      "reqps",
      "When the two lines split, the client is retrying."
    )
    b.timeseries("p99 by endpoint (client)", listOf(quantile(0.99, "client_duration_seconds", "endpoint") to "{{endpoint}}"), "s")

    b.row("2 · Load balancer (Envoy)")
    b.timeseries("Requests/s at the LB", listOf("sum(rate(envoy_http_downstream_rq_total{$LB}[RI]))" to "LB received"), "reqps")
    b.timeseries(
      "5xx at the LB vs 5xx api records itself",
      listOf(
        "sum(rate(envoy_http_downstream_rq_xx{$LB,envoy_response_code_class=\"5\"}[RI])) or vector(0)" to "5xx LB returns to clients",
        "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[RI])) or vector(0)" to "5xx api records itself",
        "sum(rate(envoy_cluster_upstream_cx_none_healthy{envoy_cluster_name=\"api\"}[RI])) or vector(0)" to "LB rejected: no healthy backend"
      ),
      "reqps",
      "Envoy counts the 503s it generates itself in upstream_rq_5xx, so compare with the errors api counts."
    )
    b.timeseries(
      "Latency p99: LB vs api",
      listOf(
        quantile(0.99, "envoy_http_downstream_rq_time", selector = "{$LB}") + " / 1000" to "LB (Envoy) p99",
        quantile(0.99, "http_server_duration_seconds", selector = "{job=\"api\"}") to "api (APM) p99"
      ),
      "s",
      "The gap between the two lines is time spent in between: network, the queue ahead of the app."
    )
    b.timeseries(
      "Healthy backends per Envoy",
      listOf("envoy_cluster_membership_healthy{envoy_cluster_name=\"api\"}" to "healthy backends", "envoy_cluster_membership_total{envoy_cluster_name=\"api\"}" to "total backends"),
      "short"
    )
    b.timeseries(
      "Connections to api failing",
      listOf(
        "rate(envoy_cluster_upstream_cx_connect_fail{envoy_cluster_name=\"api\"}[RI])" to "connect fail/s",
        "rate(envoy_cluster_upstream_rq_timeout{envoy_cluster_name=\"api\"}[RI])" to "timeout/s"
      ),
      "short"
    )

    b.row("3 · api (APM): from the moment the handler starts")
    b.timeseries("Requests/s by route and status", listOf("sum by (route, status) (rate(http_server_requests_total{job=\"api\"}[RI]))" to "{{route}} {{status}}"), "reqps")
    b.timeseries(
      "Error rate by route",
      listOf(
        "100 * (sum by (route) (rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[RI])) " +
          "or 0 * sum by (route) (rate(http_server_requests_total{job=\"api\"}[RI]))) / " +
          "sum by (route) (rate(http_server_requests_total{job=\"api\"}[RI]))" to "{{route}}"
      ),
      "percent"
    )
    b.timeseries("p99 by route", listOf(quantile(0.99, "http_server_duration_seconds", "route", "{job=\"api\"}") to "{{route}}"), "s")
    b.timeseries(
      "Little's Law: measured in-flight vs rate × duration",
      listOf(
        "avg_over_time(http_server_inflight{job=\"api\"}[1m])" to "measured in-flight",
        "sum(rate(http_server_duration_seconds_sum{job=\"api\"}[1m]))" to "rate × mean duration"
      ),
      "short",
      "rate(sum of durations) = λ × W = L. The two lines should stay close."
    )
    b.timeseries(
      "p99 success vs error (separately)",
      listOf(quantile(0.99, "http_server_duration_seconds", "outcome", "{job=\"api\"}") to "{{outcome}}"),
      "s",
      "Failed requests may be very fast (fail fast) or very slow (timeout)."
    )
    b.heatmap(
      "api latency heatmap (requests per bucket)",
      "http_server_duration_seconds",
      "{job=\"api\"}",
      description = "The whole distribution over time. A band far above the main one is a tail; dots are exemplars to open in Tempo.",
      exemplars = true
    )

    b.row("4 · Downstream as seen from api")
    b.timeseries(
      "DB session p99 (pool wait + connection held)",
      listOf(quantile(0.99, "db_client_session_seconds", "op", "{job=\"api\"}") to "{{op}}"),
      "s",
      "The 'DB span' as the app sees it: includes the wait for a connection."
    )
    b.timeseries("Queries/s sent to Postgres", listOf("sum by (job, query) (rate(db_client_queries_total[RI]))" to "{{job}} {{query}}"), "reqps")
    b.timeseries(
      "Mean query time as seen from the app",
      listOf(
        "sum by (query) (rate(db_client_duration_seconds_sum{job=\"api\"}[RI])) / " +
          "sum by (query) (rate(db_client_duration_seconds_count{job=\"api\"}[RI]))" to "{{query}}"
      ),
      "s",
      "Excludes the pool wait. Compare with exec time in row 5."
    )
    b.timeseries("Calls to pricing: p99 as seen from api", listOf(quantile(0.99, "http_client_duration_seconds", "route", "{job=\"api\"}") to "{{route}}"), "s")
    b.timeseries("Calls to pricing: outcomes/s", listOf("sum by (route, outcome) (rate(http_client_requests_total{job=\"api\"}[RI]))" to "{{route}} {{outcome}}"), "reqps")
    b.timeseries("Cache hit ratio", listOf("sum(rate(cache_requests_total{result=\"hit\"}[RI])) / sum(rate(cache_requests_total[RI]))" to "hit ratio"), "percentunit")
    b.timeseries(
      "Calls to pricing: new TCP connections/s vs calls/s",
      listOf(
        "sum(rate(http_client_connections_total{job=\"api\",target=\"pricing\"}[RI]))" to "connections opened/s",
        "sum(rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[RI]))" to "calls/s"
      ),
      "short",
      "With connection reuse, opens stay near 0 while calls flow. One open per call means every call pays a handshake."
    )

    b.row("5 · Downstream as seen by itself")
    b.timeseries("pricing: requests/s by route and status", listOf("sum by (route, status) (rate(http_server_requests_total{job=\"pricing\"}[RI]))" to "{{route}} {{status}}"), "reqps")
    b.timeseries("pricing: p99 by route (server side)", listOf(quantile(0.99, "http_server_duration_seconds", "route", "{job=\"pricing\"}") to "{{route}}"), "s")
    b.timeseries("Postgres: queries/s (counted by Postgres)", listOf("sum by (query) (rate(pg_query_calls_total[RI]))" to "{{query}}"), "reqps")
    b.timeseries(
      "Postgres: mean exec time per query",
      listOf("sum by (query) (rate(pg_query_exec_seconds_total[RI])) / sum by (query) (rate(pg_query_calls_total[RI]))" to "{{query}}"),
      "s",
      "Measured inside Postgres: includes lock waits, excludes network and the app's pool wait."
    )

    b.row("6 · Async (Kafka)")
    b.timeseries(
      "Produce/s and consume/s",
      listOf(
        "sum by (source) (rate(kafka_produced_total{outcome=\"ok\"}[RI]))" to "produce {{source}}",
        "sum(rate(kafka_consumed_total{outcome=\"ok\"}[RI]))" to "consume ok",
        "sum(rate(kafka_consumed_total{outcome=\"error\"}[RI])) or vector(0)" to "consume errors"
      ),
      "reqps"
    )
    b.timeseries("Processing time per message (p50, p99)", listOf(quantile(0.5, "kafka_processing_seconds") to "p50", quantile(0.99, "kafka_processing_seconds") to "p99"), "s")
    b.timeseries("End-to-end p99: event created to processed", listOf(quantile(0.99, "kafka_end_to_end_seconds") to "p99"), "s")
    b.timeseries("Lag by partition (messages)", listOf("kafka_consumer_lag_messages" to "p{{partition}}"), "short")
    b.timeseries("Lag by partition (seconds)", listOf("kafka_consumer_lag_seconds" to "p{{partition}}"), "s")
    b.timeseries(
      "Retries/s by partition",
      listOf("sum by (partition) (rate(kafka_consumer_retries_total[RI])) or 0 * sum by (partition) (kafka_consumer_lag_messages)" to "p{{partition}}"),
      "short"
    )
    return b
  }
}

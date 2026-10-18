package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

object UseBoard {

  private const val SERVICES = "job=~\"api|pricing|worker|loadgen|pgobserver\""

  fun build(): Board {
    val b = Board("use-layers", "02 · USE by layer", "Utilization, Saturation, Errors for each resource layer: software resource, runtime, container, host, Postgres, Kafka.")
    b.text(
      "**Each layer has its own limit.** Utilization only means something against the limit of that same layer. " +
        "Order of reading: Errors, Saturation, then Utilization.",
      height = 2
    )

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

    b.row("Runtime (process)")
    b.timeseries(
      "Event loop lag p99",
      listOf(quantile(0.99, "event_loop_lag_seconds", "job") to "{{job}}"),
      "s",
      "Saturation of a single-threaded event loop: work waiting for the loop to be free."
    )
    b.timeseries(
      "Process CPU (cores)",
      listOf("rate(process_cpu_seconds_total{$SERVICES}[RI])" to "{{job}}"),
      "short",
      "The event loop is one thread: work on it uses at most about 1 core, whatever the limit."
    )
    b.timeseries("Memory (RSS)", listOf("process_resident_memory_bytes{$SERVICES}" to "{{job}}"), "bytes")
    b.timeseries("Restarts in 10 minutes", listOf("changes(process_start_time_seconds{job=~\"api|pricing|worker|loadgen\"}[10m])" to "{{job}}"), "short")

    b.row("Container (cgroup)")
    b.timeseries("CPU usage per container (cores)", listOf("rate(cgroup_cpu_usage_seconds_total[RI])" to "{{job}}"), "short")
    b.timeseries(
      "api: CPU usage vs limit",
      listOf("rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[RI])" to "usage", "cgroup_cpu_limit_cores{job=\"api\"}" to "limit"),
      "short"
    )
    b.timeseries("Share of throttled periods", listOf("100 * rate(cgroup_cpu_throttled_periods_total[RI]) / rate(cgroup_cpu_periods_total[RI])" to "{{job}}"), "percent")
    b.timeseries("Memory vs limit", listOf("100 * cgroup_memory_usage_bytes / (cgroup_memory_limit_bytes > 0)" to "{{job}}"), "percent")
    b.timeseries(
      "PSI cpu per container (seconds waited per second)",
      listOf("rate(cgroup_pressure_waiting_seconds_total{resource=\"cpu\",kind=\"some\"}[RI])" to "{{job}}"),
      "short",
      "May be empty if the kernel has PSI off."
    )
    b.timeseries(
      "OOM kills (10 minutes)",
      listOf("increase(node_vmstat_oom_kill[10m])" to "host: any container", "increase(cgroup_memory_oom_kills_total[10m])" to "{{job}}"),
      "short",
      "When a container's main process is killed, the container restarts with a new cgroup and its own counter starts at 0. The host line still counts that kill."
    )

    b.row("Host / VM (node-exporter)")
    b.timeseries(
      "Host CPU by mode (cores)",
      listOf("sum by (mode) (rate(node_cpu_seconds_total{mode!=\"idle\"}[RI]))" to "{{mode}}", "count(node_cpu_seconds_total{mode=\"idle\"})" to "CPU count"),
      "short"
    )
    b.timeseries(
      "Load (1 min) vs CPU count",
      listOf("node_load1" to "load1", "count(node_cpu_seconds_total{mode=\"idle\"})" to "CPU count"),
      "short",
      "Load above the CPU count for long: a run queue, which is CPU saturation."
    )
    b.timeseries(
      "Host PSI: CPU and memory (seconds waited per second)",
      listOf("rate(node_pressure_cpu_waiting_seconds_total[RI])" to "cpu", "rate(node_pressure_memory_waiting_seconds_total[RI])" to "memory"),
      "short"
    )
    b.timeseries("Memory available", listOf("100 * node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes" to "available %"), "percent")
    b.timeseries("Steal time (cores)", listOf("sum(rate(node_cpu_seconds_total{mode=\"steal\"}[RI]))" to "steal"), "short")

    b.row("Postgres (server side)")
    b.timeseries("Connections by state vs max", listOf("pg_connections" to "{{state}}", "pg_max_connections" to "max_connections"), "short")
    b.timeseries("Backends waiting on a lock", listOf("pg_lock_waiters" to "lock waiters"), "short")
    b.timeseries(
      "Time Postgres spends executing (seconds per second)",
      listOf("sum(rate(pg_query_exec_seconds_total[RI]))" to "exec seconds/s"),
      "short",
      "By Little's Law, this is the mean number of queries running inside Postgres."
    )

    b.row("Kafka consumer (worker)")
    b.timeseries("Lag (seconds) by partition", listOf("kafka_consumer_lag_seconds" to "p{{partition}}"), "s")
    b.timeseries("Messages waiting in worker memory", listOf("kafka_partition_queue" to "p{{partition}}"), "short")
    b.timeseries("Processing errors/s", listOf("sum by (source) (rate(kafka_consumed_total{outcome=\"error\"}[RI])) or vector(0)" to "{{source}}"), "short")

    b.row("CPU profiles (Pyroscope)")
    b.flamegraph(
      "api: where the CPU goes",
      "{service_name=\"api\"}",
      Board.CPU_PROFILE,
      "Summed over the time range. Width is a share of CPU samples, and the x-axis is not time. Narrow the range to the incident, and compare with a range before it.",
      width = 12
    )
    b.flamegraph("pricing: where the CPU goes", "{service_name=\"pricing\"}", Board.CPU_PROFILE, width = 12)
    return b
  }
}

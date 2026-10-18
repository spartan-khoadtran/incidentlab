package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

object UseBoard {

  private const val JVM_SERVICES = "job=~\"api|pricing|worker\""
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
    b.timeseries(
      "CPU user against system (cores)",
      listOf("sum by (job, mode) (rate(cgroup_cpu_mode_seconds_total{$JVM_SERVICES}[RI]))" to "{{job}} {{mode}}"),
      "short",
      "From the container's cpu.stat. System time is the kernel working for the process: syscalls such as writes to a socket, a pipe or a file."
    )

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

    b.row("JVM (heap, GC, virtual threads)")
    b.timeseries(
      "api heap by pool against the old gen max",
      listOf("jvm_memory_used_bytes{job=\"api\",area=\"heap\"}" to "{{id}}", "jvm_memory_max_bytes{job=\"api\",id=\"Tenured Gen\"}" to "Tenured Gen max"),
      "bytes",
      "Serial collector pools: Eden Space and Survivor Space are the young generation, Tenured Gen the old one. Old gen usage grows between full GCs."
    )
    b.timeseries(
      "GC pause p99",
      listOf(quantile(0.99, "jvm_gc_pause_seconds", "job", "{$JVM_SERVICES}") to "{{job}}"),
      "s",
      "Every pause stops all application threads. A request in flight during a pause waits for it."
    )
    b.heatmap(
      "api GC pauses (pauses per bucket)",
      "jvm_gc_pause_seconds",
      "{job=\"api\"}",
      description = "One cell per pause-length bucket and interval. A full GC shows as a cell far above the young GC band."
    )
    b.timeseries(
      "GC runs per second by collector and cause",
      listOf("sum by (job, gc, cause) (rate(jvm_gc_pause_seconds_count{$JVM_SERVICES}[RI]))" to "{{job}} {{gc}} {{cause}}"),
      "short",
      "Copy is the young collector, MarkSweepCompact the full one."
    )
    b.timeseries(
      "Allocation rate",
      listOf("rate(jvm_gc_memory_allocated_bytes_total{$JVM_SERVICES}[RI])" to "{{job}}"),
      "Bps",
      "Bytes allocated in the young generation per second, counted at each GC."
    )
    b.timeseries(
      "Live data after full GC against the old gen max",
      listOf(
        "jvm_gc_live_data_size_bytes{$JVM_SERVICES}" to "{{job}} live after GC",
        "jvm_memory_max_bytes{$JVM_SERVICES,id=\"Tenured Gen\"}" to "{{job}} old gen max"
      ),
      "bytes",
      "What a full GC could not free. Close to the max: each GC frees little, so the next one comes soon."
    )
    b.timeseries(
      "Pinned virtual threads per second",
      listOf("rate(jvm_virtual_thread_pinned_total{$JVM_SERVICES}[RI])" to "{{job}}"),
      "short",
      "JFR jdk.VirtualThreadPinned events above 1 ms: a virtual thread blocked while it could not leave its carrier thread."
    )
    b.timeseries(
      "Time pinned per second (carriers blocked)",
      listOf("rate(jvm_virtual_thread_pinned_seconds_total{$JVM_SERVICES}[RI])" to "{{job}}"),
      "short",
      "Seconds of pinned blocking per second, about the number of carriers blocked at once. api has 2 carriers, one per core."
    )
    return b
  }
}

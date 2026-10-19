package com.c0x12c.incidentlab.dashboards

import com.c0x12c.incidentlab.dashboards.Board.Companion.quantile

object RuntimeBoard {

  private const val JVM_SERVICES = "job=~\"api|pricing|worker\""
  private const val SERVICES = "job=~\"api|pricing|worker|loadgen|pgobserver\""

  fun build(): Board {
    val b = Board("use-runtime", "03 · USE · Runtime and JVM", "The process, the JVM (heap, GC, virtual threads) and where the CPU goes.")
    b.useIntro()

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

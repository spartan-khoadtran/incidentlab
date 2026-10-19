package com.c0x12c.incidentlab.dashboards

object HostBoard {

  private const val SERVICES = "job=~\"api|pricing|worker|loadgen|pgobserver\""

  fun build(): Board {
    val b = Board("use-host", "04 · USE · Container, host, network", "The cgroup limits of each container, the host or VM under them, and TCP per service.")
    b.useIntro()

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

    b.row("Network (per service)")
    b.timeseries(
      "TCP segments retransmitted/s",
      listOf("rate(net_tcp_retrans_segs_total{$SERVICES}[RI])" to "{{job}}"),
      "short",
      "Counted by the kernel in each container's own network namespace. Near 0 on a healthy network."
    )
    b.timeseries(
      "RTO timeouts and tail loss probes/s",
      listOf("rate(net_tcp_timeouts_total{$SERVICES}[RI])" to "{{job}} RTO", "rate(net_tcp_loss_probes_total{$SERVICES}[RI])" to "{{job}} probe"),
      "short",
      "Each one is a sender that waited for a lost segment. On Linux an RTO waits at least 200 ms."
    )
    b.timeseries("TCP connections opened/s (active opens)", listOf("rate(net_tcp_active_opens_total{$SERVICES}[RI])" to "{{job}}"), "short")
    b.timeseries(
      "Sockets in TIME_WAIT",
      listOf("net_tcp_time_wait{$SERVICES}" to "{{job}}"),
      "short",
      "The side that closes first keeps the socket for 60 s. About closes per second x 60."
    )
    b.timeseries("TCP connections established", listOf("net_tcp_current_established{$SERVICES}" to "{{job}}"), "short")
    return b
  }
}

package com.c0x12c.incidentlab.dashboards

object DataBoard {

  fun build(): Board {
    val b = Board("use-data", "05 · USE · Postgres and disk", "Postgres from the server side, and the host disks under it.")
    b.useIntro()

    b.row("Postgres (server side)")
    b.timeseries("Connections by state vs max", listOf("pg_connections" to "{{state}}", "pg_max_connections" to "max_connections"), "short")
    b.timeseries("Backends waiting on a lock", listOf("pg_lock_waiters" to "lock waiters"), "short")
    b.timeseries(
      "Time Postgres spends executing (seconds per second)",
      listOf("sum(rate(pg_query_exec_seconds_total[RI]))" to "exec seconds/s"),
      "short",
      "By Little's Law, this is the mean number of queries running inside Postgres."
    )
    b.timeseries(
      "Wait events (mean backends waiting)",
      listOf("pg_wait_events > 0" to "{{type}} / {{event}}"),
      "short",
      "Sampled every 100 ms from pg_stat_activity. IO / WALSync: waiting for a WAL fsync (a commit or the WAL writer). IO / DataFileSync: mostly the checkpointer flushing table files.",
      stack = true
    )
    b.timeseries(
      "Mean WAL sync time",
      listOf("rate(pg_wal_sync_seconds_total[RI]) / rate(pg_wal_syncs_total[RI])" to "per WAL fsync"),
      "s",
      "Every commit waits for one of these. It grows when the disk under the WAL is busy."
    )
    b.timeseries(
      "WAL written per second",
      listOf("rate(pg_wal_bytes_total[RI])" to "WAL bytes/s"),
      "Bps",
      "The first change to a page after a checkpoint writes an image of the whole page to WAL, so WAL jumps right after each checkpoint."
    )
    b.timeseries(
      "Checkpoints in the last minute, by kind",
      listOf("increase(pg_checkpoints_total[1m])" to "{{kind}}"),
      "short",
      "timed: checkpoint_timeout fired. requested: WAL reached max_wal_size (or someone ran CHECKPOINT)."
    )
    b.timeseries(
      "Checkpoint write and sync (seconds per second)",
      listOf("rate(pg_checkpoint_write_seconds_total[1m])" to "write", "rate(pg_checkpoint_sync_seconds_total[1m])" to "sync"),
      "short",
      "Both counters move once per checkpoint, when it ends. Write time includes the pauses that spread a checkpoint out, so a long write is healthy. Sync time is the burst of flushes at the end."
    )

    b.row("Disk (host, node-exporter)")
    b.timeseries(
      "IOPS by device",
      listOf("rate(node_disk_writes_completed_total[RI])" to "{{device}} writes", "rate(node_disk_reads_completed_total[RI])" to "{{device}} reads"),
      "short",
      "Completed I/Os per second as the host sees each disk. A cgroup IOPS limit holds I/O back before it reaches this count."
    )
    b.timeseries(
      "Throughput by device",
      listOf("rate(node_disk_written_bytes_total[RI])" to "{{device}} written", "rate(node_disk_read_bytes_total[RI])" to "{{device}} read"),
      "Bps"
    )
    b.timeseries(
      "Await (mean time per I/O)",
      listOf(
        "rate(node_disk_write_time_seconds_total[RI]) / rate(node_disk_writes_completed_total[RI])" to "{{device}} write",
        "rate(node_disk_read_time_seconds_total[RI]) / rate(node_disk_reads_completed_total[RI])" to "{{device}} read"
      ),
      "s",
      "Queue time in the device plus service time, like iostat's w_await and r_await."
    )
    b.timeseries(
      "Busy share (utilization)",
      listOf("100 * rate(node_disk_io_time_seconds_total[RI])" to "{{device}}"),
      "percent",
      "Share of time with at least one I/O in flight, like iostat's %util. A disk that serves I/Os in parallel can take more work at 100%."
    )
    b.timeseries(
      "Queue (mean I/Os in flight)",
      listOf("rate(node_disk_io_time_weighted_seconds_total[RI])" to "{{device}}"),
      "short",
      "Saturation, like iostat's aqu-sz. By Little's Law it is IOPS times await."
    )
    return b
  }
}

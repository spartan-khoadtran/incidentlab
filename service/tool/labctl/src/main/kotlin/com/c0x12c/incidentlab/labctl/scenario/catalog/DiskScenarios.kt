package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.control.CgroupApplier
import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.exp
import kotlin.math.roundToInt

/** Faults on the disk under Postgres: a neighbour's fsyncs, checkpoint bursts, and an IOPS quota. */
internal object DiskScenarios {

  val all: List<Scenario> = listOf(
    walFsyncContention(),
    checkpointBursts(),
    iopsLimit()
  )

  private fun walFsyncContention() = Scenario(
    id = "wal_fsync_contention",
    title = "A batch job fills the database disk",
    level = 2,
    topics = listOf("disk", "use", "layers", "db"),
    params = { random, _ -> mapOf("mb" to random.nextInt(MIN_FSYNC_MB / FSYNC_STEP, MAX_FSYNC_MB / FSYNC_STEP + 1) * FSYNC_STEP) },
    faults = { p -> FaultSpec(services = mapOf("noisy" to mapOf("fsync_mb_per_s" to p.int("mb")))) },
    marker = { "Batch job: nightly export started on db-host" },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 went up several times. /products looks normal. " +
        "The DBA checked Postgres: no slow query, no lock waits. Nothing was deployed on the app side."
    },
    hints = listOf(
      "Which routes slowed down, and what does each one do in Postgres: read or write?",
      "Postgres row: which wait event grew? Compare the mean WAL sync time with exec time by query.",
      "Host disk row: await and busy share. Postgres sends the same traffic as before, so who else writes to that disk?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("disk"), signal = setOf("disk_io"), cause = "disk_io_contention"),
    explain = { p ->
      "The nightly export (the noisy container) writes ${p.int("mb")} MB/s in 1 MB chunks and calls fsync after each one: " +
        "${p.int("mb")} fsyncs per second, each pushing 1 MB and a cache flush to the disk that also holds pgdata. " +
        "Postgres commits about $COMMITS_PER_SECOND transactions per second ($CHECKOUTS_PER_SECOND checkouts from api and $CHECKOUTS_PER_SECOND orders marked processed by the worker), " +
        "and each commit waits for its WAL flush. Those small flushes now queue behind the export's megabytes, so the mean WAL sync time and IO / WALSync grow. " +
        "/checkout commits, so it slows down. /products reads from Redis and never waits for WAL, so it stays fast. " +
        "No query plan changed and no lock is held, which is why the DBA saw nothing in the query view. " +
        "The host disk row shows the busy share and the await up while Postgres's own traffic is flat. " +
        "Fix: move the export to another disk, or limit its I/O (ionice, a cgroup io.max), or run it outside peak hours."
    },
    steps = listOf(
      "Problem statement: /checkout p99 up, /products normal, no slow query or lock in Postgres, no app deploy.",
      "RED: the rate is flat, so suspect architecture. Only the route that writes slowed down.",
      "Postgres: exec time by query is flat and pg_lock_waiters stays at 0. Postgres times each statement, not the WAL flush at its commit.",
      "Wait events: IO / WALSync grew, and the mean WAL sync time is many times its baseline. Commits wait for the disk.",
      "Host disk row: busy share and await up, while Postgres sends the same work as before. Someone else uses the disk.",
      "Throughput by device and the noisy job's fsync rate: the nightly export makes most of the disk's write traffic.",
      "The cause: another process saturates the disk under the WAL. Commits pay for it, cached reads do not."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))",
      "rate(pg_wal_sync_seconds_total[1m]) / rate(pg_wal_syncs_total[1m])",
      "pg_wait_events{type=\"IO\"}",
      "rate(node_disk_write_time_seconds_total[1m]) / rate(node_disk_writes_completed_total[1m])",
      "100 * rate(node_disk_io_time_seconds_total[1m])",
      "rate(node_disk_written_bytes_total[1m])",
      "rate(noisy_fsync_total[1m])"
    ),
    cli = listOf(
      "iostat -xz 1 5   # %util and w_await of the disk under the volumes climb while the export runs",
      "pidstat -d -l 1 5   # the noisy java process writes tens of MB/s (kB_wr/s), postgres writes little",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE wait_event IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC\"   # IO WALSync shows up again and again, no Lock rows",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c 'SELECT wal_sync, wal_sync_time FROM pg_stat_wal'   # run it twice a minute apart: sync time grows much faster than the sync count"
    ),
    doc = "Postgres and the disk > WAL and fsync at commit; Disk math > Await and the queue"
  )

  private fun checkpointBursts() = Scenario(
    id = "checkpoint_bursts",
    title = "Latency spikes every half minute",
    level = 3,
    topics = listOf("disk", "db", "use"),
    params = { random, _ -> mapOf("rows" to random.nextInt(MIN_STATS_ROWS, MAX_STATS_ROWS + 1)) },
    faults = { p ->
      FaultSpec(
        services = mapOf(
          "pgobserver" to mapOf(
            "checkpoint_timeout_s" to CHECKPOINT_TIMEOUT_S,
            "checkpoint_completion_target" to CHECKPOINT_TARGET,
            "stats_rows_per_s" to p.int("rows")
          )
        )
      )
    },
    marker = { "Config change: postgres checkpoint tuning" },
    decoy = 0.3,
    ticket = { p ->
      "Since ${p.startedAt}, /checkout p99 spikes about every half minute on a 20 s window, then drops back. p50 is flat. " +
        "At the same time the DBA applied new Postgres settings for faster crash recovery and moved the review stats job onto this database. " +
        "That job ran for months on the old host without trouble."
    },
    hints = listOf(
      "Spikes on a fixed period: what in the system runs on a timer? Line the spikes up with the Postgres row.",
      "Checkpoints in the last minute by kind, and WAL written per second: how often, and what happens right after each checkpoint?",
      "checkpoint_timeout and checkpoint_completion_target: over how many seconds does each checkpoint write its pages now?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("db", "disk"), signal = setOf("disk_io"), cause = "checkpoint_bursts"),
    explain = { p ->
      val perInterval = p.int("rows") * CHECKPOINT_TIMEOUT_S
      val pages = (STATS_PAGES * (1 - exp(-perInterval.toDouble() / STATS_PAGES))).roundToInt()
      val mb = pages * PAGE_KB / KB_PER_MB
      val window = CHECKPOINT_TIMEOUT_S * CHECKPOINT_TARGET
      "The DBA set checkpoint_timeout to $CHECKPOINT_TIMEOUT_S s (from 300) and checkpoint_completion_target to $CHECKPOINT_TARGET (from 0.9), " +
        "and reloaded. The review stats job rewrites ${p.int("rows")} random rows per second. " +
        "In $CHECKPOINT_TIMEOUT_S s that is $perInterval updates over about $STATS_PAGES pages, so about $pages pages (${p.fixed(mb, 1)} MB) are dirty at each checkpoint. " +
        "Before, a checkpoint spread its writes over 300 x 0.9 = 270 s. Now it writes them in $CHECKPOINT_TIMEOUT_S x $CHECKPOINT_TARGET = ${p.fixed(window, 0)} s " +
        "(about ${p.fixed(mb / window, 1)} MB/s) and flushes them at once, every $CHECKPOINT_TIMEOUT_S s. " +
        "Right after each checkpoint starts, the first change to every page also writes a full page image to the WAL: about ${p.fixed(mb, 0)} MB of WAL, " +
        "most of it in the first 10 s or so, and every commit must flush its WAL behind it. " +
        "The commits that land in those seconds wait behind the burst. Only a few percent of checkouts do, so p50 does not move, and p99 spikes " +
        "every $CHECKPOINT_TIMEOUT_S s. Read it on a short window such as 20 s: a 1-minute window holds two bursts and looks flat and high. " +
        "The job alone writes at a steady rate: a steady load cannot make a 30 s pattern, the checkpoint timer does. " +
        "Fix: put the three settings back (or at least a completion target near 0.9 and a timeout of minutes), with a reload."
    },
    steps = listOf(
      "Problem statement: /checkout p99 spikes about every 30 s, p50 is flat. Two changes at the start: new Postgres settings and the stats job.",
      "RED: the rate is flat, so suspect architecture. A fixed period points at something on a timer.",
      "Postgres row: checkpoints jump from one every 5 minutes to about two a minute, and the WAL rate jumps right after each one.",
      "Wait events and the host disk: IO / DataFileSync and IO / WALSync peak on the same 30 s beat as the p99 spikes.",
      "Settings: checkpoint_timeout 30 s and checkpoint_completion_target 0.1 squeeze each checkpoint's writes into about 3 s.",
      "The stats job writes at a steady rate. It supplies the dirty pages but cannot make the 30 s pattern by itself.",
      "The cause: checkpoint bursts. Each checkpoint writes and flushes its pages at once, and the commits in that window wait."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[20s])))",
      "histogram_quantile(0.5, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[20s])))",
      "increase(pg_checkpoints_total[1m])",
      "rate(pg_wal_bytes_total[1m])",
      "rate(pg_checkpoint_sync_seconds_total[1m])",
      "pg_wait_events{type=\"IO\"}",
      "100 * rate(node_disk_io_time_seconds_total[1m])"
    ),
    cli = listOf(
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"SELECT name, setting, sourcefile FROM pg_settings WHERE name IN ('checkpoint_timeout', 'checkpoint_completion_target')\"   # 30 and 0.1, set in postgresql.auto.conf",
      "for i in 1 2; do PGPASSWORD=lab psql -h postgres -U lab lab -c 'SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time, buffers_checkpoint FROM pg_stat_bgwriter'; sleep 60; done   # about two more checkpoints a minute later, about 16,000 buffers each",
      "iostat -xz 1 60   # writes to the disk come in bursts every 30 s, quiet in between"
    ),
    doc = "Postgres and the disk > Checkpoints; Disk panels in this lab > The Postgres row"
  )

  private fun iopsLimit() = Scenario(
    id = "iops_limit",
    title = "Database I/O slow on an idle disk",
    level = 3,
    topics = listOf("disk", "db", "use"),
    params = { random, _ -> mapOf("iops" to random.nextInt(MIN_WRITE_IOPS / IOPS_STEP, MAX_WRITE_IOPS / IOPS_STEP + 1) * IOPS_STEP) },
    faults = { p -> FaultSpec(docker = mapOf("postgres" to mapOf(CgroupApplier.WRITE_IOPS to p.int("iops")))) },
    decoy = 0.5,
    ticket = { p ->
      "Since ${p.startedAt}, /checkout p99 has climbed. The DBA sees Postgres sessions waiting on I/O. " +
        "The infra team says the disk is nearly idle and refuses to look further."
    },
    hints = listOf(
      "Both teams can be right. Compare what Postgres waits for with what the host disk does.",
      "Mean WAL sync time against host disk await: do they rise together?",
      "The kernel can cap one container's I/O before it reaches the device. Read the postgres container's cgroup io.max and compare it with the WAL syncs per second."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("disk"), signal = setOf("disk_io"), cause = "iops_limit", partial = setOf("disk_io_contention")),
    explain = { p ->
      val share = BASE_WRITE_IOS.toDouble() / p.int("iops") * PERCENT
      "The postgres container got a write limit of ${p.int("iops")} IOPS on its disk: one line, `<disk> wiops=${p.int("iops")}`, in its cgroup io.max. " +
        "Postgres needs about $BASE_WRITE_IOS write I/Os per second just for the WAL flushes of about $COMMITS_PER_SECOND commits per second, " +
        "about ${share.roundToInt()}% of the quota, before the background writer and the checkpoints add their pages. " +
        "Every burst above ${p.int("iops")} per second waits for the next tokens, so the mean WAL sync time and IO / WALSync grow, and so does /checkout p99. " +
        "The kernel holds those writes back before they reach the device. The host disk sees fewer I/Os, a low await and a low busy share: " +
        "infra is right that the disk is idle, and the DBA is right that Postgres waits. The quota sits between them. " +
        "On a cloud volume the same limit sits below the guest, so there the await rises too, but the IOPS still stay flat at the limit. " +
        "Fix: raise the limit (provisioned IOPS, a bigger volume), or make fewer, larger writes."
    },
    steps = listOf(
      "Problem statement: /checkout p99 up, Postgres waits on I/O, the disk looks idle to infra.",
      "RED: the rate is flat, so suspect architecture. Only the route that writes slowed down.",
      "Postgres: IO / WALSync grew and the mean WAL sync time is many times its baseline.",
      "Host disk row: await and busy share are low and flat. The disk itself is not the bottleneck.",
      "The gap between Postgres's I/O time and the device await means the wait sits above the device.",
      "The postgres cgroup io.max holds a write quota at one round number. The WAL flushes alone use a large share of it, so every burst waits.",
      "The cause: an IOPS limit between the database and an idle disk."
    ),
    evidence = listOf(
      "rate(pg_wal_sync_seconds_total[1m]) / rate(pg_wal_syncs_total[1m])",
      "rate(node_disk_write_time_seconds_total[1m]) / rate(node_disk_writes_completed_total[1m])",
      "100 * rate(node_disk_io_time_seconds_total[1m])",
      "rate(node_disk_writes_completed_total[1m])",
      "rate(pg_wal_syncs_total[1m])",
      "pg_wait_events{type=\"IO\"}"
    ),
    cli = listOf(
      "cat $(cg $(pgrep -o -x postgres))/io.max   # a line <maj:min> ... wiops=N: the write quota on the postgres cgroup",
      "iostat -xz 1 5   # w_await and %util of that disk stay low: the device is idle",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE wait_event IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC\"   # IO WALSync shows up again and again although the disk is idle"
    ),
    doc = "Postgres and the disk > IOPS limits in the cloud; Disk panels in this lab > The Postgres row"
  )

  // About one commit per checkout from api and one per order the worker marks processed.
  private val COMMITS_PER_SECOND = 2 * CHECKOUTS_PER_SECOND
  private val BASE_WRITE_IOS = COMMITS_PER_SECOND

  private const val MIN_FSYNC_MB = 40
  private const val MAX_FSYNC_MB = 120
  private const val FSYNC_STEP = 10

  private const val CHECKPOINT_TIMEOUT_S = 30
  private const val CHECKPOINT_TARGET = 0.1
  private const val MIN_STATS_ROWS = 2_000
  private const val MAX_STATS_ROWS = 3_000

  // review_stats: 1,280,000 rows at fillfactor 50, about 78 rows per 8 kB page.
  private const val STATS_PAGES = 16_410
  private const val PAGE_KB = 8.0
  private const val KB_PER_MB = 1_024.0

  // Below about 60 the quota leaves little room over the WAL flushes alone, so the wait shows on every run.
  private const val MIN_WRITE_IOPS = 40
  private const val MAX_WRITE_IOPS = 60
  private const val IOPS_STEP = 5
}

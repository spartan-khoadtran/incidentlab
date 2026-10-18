package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.control.CgroupApplier
import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults on the disk under Postgres: an IOPS quota on its volume. */
internal object DiskScenarios {

  val all: List<Scenario> = listOf(
    iopsLimit()
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
    answer = Answer(nature = setOf("arch"), location = setOf("disk"), signal = setOf("disk_io"), cause = "iops_limit"),
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
      "cat $(cg $(pid postgres))/io.max   # a line <maj:min> ... wiops=N: the write quota on the postgres cgroup",
      "iostat -xz 1 5   # w_await and %util of that disk stay low: the device is idle",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE wait_event IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC\"   # IO WALSync shows up again and again although the disk is idle"
    ),
    doc = "Postgres and the disk > IOPS limits in the cloud; Disk panels in this lab > The Postgres row"
  )

  // About one commit per checkout from api and one per order the worker marks processed.
  private val COMMITS_PER_SECOND = 2 * CHECKOUTS_PER_SECOND
  private val BASE_WRITE_IOS = COMMITS_PER_SECOND

  // Below about 60 the quota leaves little room over the WAL flushes alone, so the wait shows on every run.
  private const val MIN_WRITE_IOPS = 40
  private const val MAX_WRITE_IOPS = 60
  private const val IOPS_STEP = 5
}

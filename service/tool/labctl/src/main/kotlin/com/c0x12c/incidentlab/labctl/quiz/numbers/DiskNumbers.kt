package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** The arithmetic of docs/disk.md, kept apart from the question texts so a test can check it by hand. */
internal object DiskMath {
  const val KIB_PER_MIB = 1_024.0
  const val PAGE_KIB = 8.0
  const val MILLIS_PER_SECOND = 1_000.0
  const val PERCENT = 100.0
  const val GP2_IOPS_PER_GIB = 3
  const val GP2_MIN_IOPS = 100

  fun throughputMib(iops: Int, ioKib: Int): Double = iops * ioKib / KIB_PER_MIB

  fun maxIops(iopsLimit: Int, throughputMib: Int, ioKib: Int): Double = minOf(iopsLimit.toDouble(), throughputMib * KIB_PER_MIB / ioKib)

  fun utilizationPercent(iops: Int, serviceMs: Double): Double = iops * serviceMs / MILLIS_PER_SECOND * PERCENT

  fun serviceMs(utilizationPercent: Int, iops: Int): Double = utilizationPercent / PERCENT / iops * MILLIS_PER_SECOND

  fun queue(iops: Int, awaitMs: Double): Double = iops * awaitMs / MILLIS_PER_SECOND

  fun awaitMs(serviceMs: Double, utilizationPercent: Int): Double = serviceMs / (1 - utilizationPercent / PERCENT)

  fun commitsPerFlush(commitsPerSecond: Int, syncsPerSecond: Int): Double = commitsPerSecond.toDouble() / syncsPerSecond

  fun meanWaiting(perSecond: Int, durationMs: Double): Double = perSecond * durationMs / MILLIS_PER_SECOND

  fun requestedCheckpointWalMb(maxWalMb: Int, completionTarget: Double): Double = maxWalMb / (1 + completionTarget)

  fun checkpointIntervalSeconds(walMbPerSecond: Double, maxWalMb: Int, completionTarget: Double, timeoutSeconds: Int): Double =
    minOf(timeoutSeconds.toDouble(), requestedCheckpointWalMb(maxWalMb, completionTarget) / walMbPerSecond)

  fun checkpointWriteMibPerSecond(dirtyPages: Int, timeoutSeconds: Int, completionTarget: Double): Double =
    dirtyPages * PAGE_KIB / KIB_PER_MIB / (timeoutSeconds * completionTarget)

  fun backlog(arrivingPerSecond: Int, limit: Int, seconds: Int): Int = (arrivingPerSecond - limit) * seconds

  fun gp2BaselineIops(sizeGib: Int): Int = maxOf(GP2_MIN_IOPS, GP2_IOPS_PER_GIB * sizeGib)

  fun awaitFromQueueMs(queue: Double, iops: Int): Double = queue / iops * MILLIS_PER_SECOND

  fun meanSyncMs(syncSeconds: Double, syncs: Int): Double = syncSeconds / syncs * MILLIS_PER_SECOND
}

/** Numeric drills for docs/disk.md. */
internal object DiskNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::throughput, ::throughputCap, ::utilization, ::serviceTime, ::queue, ::await, ::commitsPerFlush, ::checkpointTrigger,
    ::checkpointInterval, ::checkpointWriteRate, ::walSyncWaiters, ::backlog, ::gp2Baseline, ::panelAwait, ::panelWalSync
  )

  private fun throughput(r: Random): NumericQuestion {
    val iops = listOf(500, 1000, 2000, 3000, 6000).random(r)
    val kib = listOf(4, 8, 16, 64).random(r)
    val answer = DiskMath.throughputMib(iops, kib)
    return NumericQuestion(
      "A volume serves $iops IOPS of $kib KiB each. What is its throughput in MiB/s?",
      answer, Tolerance.Relative(0.02), "MiB/s",
      "Throughput = IOPS × size = $iops × $kib KiB = ${iops * kib} KiB/s, and / 1024 = ${shown(answer)} MiB/s.",
      "Disk math > IOPS, size and throughput"
    )
  }

  private fun throughputCap(r: Random): NumericQuestion {
    val iopsLimit = listOf(3000, 6000, 10000).random(r)
    val mib = listOf(125, 250, 500).random(r)
    val kib = listOf(16, 64, 128, 256).random(r)
    val answer = DiskMath.maxIops(iopsLimit, mib, kib)
    val byThroughput = mib * DiskMath.KIB_PER_MIB / kib
    return NumericQuestion(
      "A volume allows $iopsLimit IOPS and $mib MiB/s. The app writes $kib KiB I/Os. How many IOPS can it really get?",
      answer, Tolerance.Relative(0.02), "IOPS",
      "The throughput limit allows $mib × 1024 / $kib = ${shown(byThroughput)} IOPS of $kib KiB. The lower of $iopsLimit and ${shown(byThroughput)} wins: ${shown(answer)} IOPS.",
      "Disk math > IOPS, size and throughput"
    )
  }

  private fun utilization(r: Random): NumericQuestion {
    val iops = listOf(100, 200, 300, 400).random(r)
    val ms = listOf(0.5, 1.0, 2.0).random(r)
    val answer = DiskMath.utilizationPercent(iops, ms)
    return NumericQuestion(
      "A disk completes $iops I/Os per second. Each one takes ${shown(ms)} ms of device time. What busy share (%util) does iostat show?",
      answer, Tolerance.Absolute(0.5), "%",
      "Utilization law: U = X × S = $iops × ${shown(ms / DiskMath.MILLIS_PER_SECOND)} s = ${shown(answer / DiskMath.PERCENT)}, so ${shown(answer)}%.",
      "Disk math > Utilization and service time"
    )
  }

  private fun serviceTime(r: Random): NumericQuestion {
    val util = listOf(30, 45, 60, 75, 90).random(r)
    val iops = listOf(150, 300, 500, 600).random(r)
    val answer = DiskMath.serviceMs(util, iops)
    return NumericQuestion(
      "A disk is $util% busy at $iops IOPS. What is the mean service time per I/O in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "S = U / X = ${shown(util / DiskMath.PERCENT)} / $iops = ${shown(answer / DiskMath.MILLIS_PER_SECOND)} s, so ${shown(answer)} ms.",
      "Disk math > Utilization and service time"
    )
  }

  private fun queue(r: Random): NumericQuestion {
    val iops = listOf(200, 400, 800, 1500).random(r)
    val await = listOf(0.5, 2.0, 5.0, 12.0).random(r)
    val answer = DiskMath.queue(iops, await)
    return NumericQuestion(
      "A disk does $iops IOPS with an await of ${shown(await)} ms. How many I/Os are in flight on average (aqu-sz)?",
      answer, Tolerance.Relative(0.02), "I/Os",
      "Little's Law: queue = IOPS × await = $iops × ${shown(await / DiskMath.MILLIS_PER_SECOND)} s = ${shown(answer)}.",
      "Disk math > Await and the queue"
    )
  }

  private fun await(r: Random): NumericQuestion {
    val s = listOf(0.2, 0.5, 1.0, 2.0).random(r)
    val util = listOf(50, 60, 75, 80, 90).random(r)
    val answer = DiskMath.awaitMs(s, util)
    return NumericQuestion(
      "A disk has a service time of ${shown(s)} ms and is $util% busy. By M/M/1, what is the mean await in ms?",
      answer, Tolerance.Relative(0.03), "ms",
      "await ≈ S / (1 − U) = ${shown(s)} / (1 − ${shown(util / DiskMath.PERCENT)}) = ${shown(answer)} ms, ${shown(answer / s)} times the service time.",
      "Disk math > Await and the queue"
    )
  }

  private fun commitsPerFlush(r: Random): NumericQuestion {
    val syncs = listOf(20, 30, 40, 50).random(r)
    val commits = syncs * listOf(1.0, 1.5, 2.0, 3.0).random(r)
    val answer = DiskMath.commitsPerFlush(commits.toInt(), syncs)
    return NumericQuestion(
      "Postgres commits ${commits.toInt()} transactions per second and pg_stat_wal shows $syncs WAL syncs per second. How many commits share one flush on average?",
      answer, Tolerance.Relative(0.02), "commits per flush",
      "${commits.toInt()} / $syncs = ${shown(answer)}. One flush covers every commit whose WAL is already in the buffers, so commits that arrive together share it.",
      "Postgres and the disk > WAL and fsync at commit"
    )
  }

  private fun checkpointTrigger(r: Random): NumericQuestion {
    val maxWal = listOf(1024, 2048, 4096, 8192).random(r)
    val target = listOf(0.5, 0.7, 0.9).random(r)
    val answer = DiskMath.requestedCheckpointWalMb(maxWal, target)
    return NumericQuestion(
      "max_wal_size is $maxWal MB and checkpoint_completion_target is ${shown(target)}. About how many MB of WAL since the last checkpoint start a requested checkpoint?",
      answer, Tolerance.Relative(0.05), "MB",
      "About max_wal_size / (1 + target) = $maxWal / ${shown(1 + target)} = ${shown(answer)} MB. Postgres counts whole 16 MB segments, so the real point can be a segment or two lower.",
      "Postgres and the disk > Checkpoints"
    )
  }

  private fun checkpointInterval(r: Random): NumericQuestion {
    val rate = listOf(0.5, 2.0, 5.0, 10.0).random(r)
    val maxWal = listOf(1024, 2048).random(r)
    val timeout = listOf(300, 600, 900).random(r)
    val target = 0.9
    val answer = DiskMath.checkpointIntervalSeconds(rate, maxWal, target, timeout)
    val byWal = DiskMath.requestedCheckpointWalMb(maxWal, target) / rate
    return NumericQuestion(
      "Postgres writes ${shown(rate)} MB of WAL per second. max_wal_size is $maxWal MB, checkpoint_completion_target 0.9, checkpoint_timeout $timeout s. About how many seconds pass between checkpoints?",
      answer, Tolerance.Relative(0.05), "s",
      "WAL starts a checkpoint after about $maxWal / 1.9 = ${shown(DiskMath.requestedCheckpointWalMb(maxWal, target))} MB, that is ${shown(byWal)} s at ${shown(rate)} MB/s. " +
        "The timeout fires at $timeout s. Whichever comes first wins: ${shown(answer)} s.",
      "Postgres and the disk > Checkpoints"
    )
  }

  private fun checkpointWriteRate(r: Random): NumericQuestion {
    val pages = listOf(1280, 2560, 5120, 12800).random(r)
    val (timeout, target) = listOf(300 to 0.9, 30 to 0.1, 60 to 0.5, 30 to 0.9).random(r)
    val answer = DiskMath.checkpointWriteMibPerSecond(pages, timeout, target)
    return NumericQuestion(
      "A checkpoint must write $pages dirty 8 kB pages. checkpoint_timeout is $timeout s and checkpoint_completion_target ${shown(target)}. About how many MiB/s does the checkpoint write?",
      answer, Tolerance.Relative(0.03), "MiB/s",
      "$pages × 8 KiB = ${shown(pages * DiskMath.PAGE_KIB / DiskMath.KIB_PER_MIB)} MiB, spread over $timeout × ${shown(target)} = ${shown(timeout * target)} s: ${shown(answer)} MiB/s. A lower target squeezes the same pages into a shorter burst.",
      "Postgres and the disk > Checkpoints"
    )
  }

  private fun walSyncWaiters(r: Random): NumericQuestion {
    val rate = listOf(30, 60, 120, 200).random(r)
    val ms = listOf(0.5, 1.0, 4.0, 10.0).random(r)
    val answer = DiskMath.meanWaiting(rate, ms)
    return NumericQuestion(
      "Postgres does $rate WAL syncs per second and each takes ${shown(ms)} ms. On average, how many processes wait on IO / WALSync at any moment?",
      answer, Tolerance.Relative(0.02), "processes",
      "Little's Law: $rate × ${shown(ms / DiskMath.MILLIS_PER_SECOND)} s = ${shown(answer)}. One look at pg_stat_activity catches it only that share of the time, so sample often and average.",
      "Postgres and the disk > Wait events"
    )
  }

  private fun backlog(r: Random): NumericQuestion {
    val limit = listOf(40, 60, 80, 3000).random(r)
    val arriving = limit + listOf(10, 20, 40).random(r) * if (limit > 100) 10 else 1
    val seconds = listOf(5, 10, 30).random(r)
    val answer = DiskMath.backlog(arriving, limit, seconds).toDouble()
    return NumericQuestion(
      "Writes arrive at $arriving per second. The volume allows $limit write IOPS. How many writes wait after $seconds seconds?",
      answer, Tolerance.Relative(0.0), "writes",
      "The backlog grows by $arriving − $limit = ${arriving - limit} writes every second: ${arriving - limit} × $seconds = ${shown(answer)}. Completed IOPS stay flat at $limit while the backlog grows.",
      "Postgres and the disk > IOPS limits in the cloud"
    )
  }

  private fun gp2Baseline(r: Random): NumericQuestion {
    val size = listOf(20, 30, 100, 250, 500, 1000).random(r)
    val answer = DiskMath.gp2BaselineIops(size).toDouble()
    return NumericQuestion(
      "What is the baseline IOPS of an AWS gp2 volume of $size GiB?",
      answer, Tolerance.Relative(0.0), "IOPS",
      "gp2 gives 3 IOPS per GiB with a floor of 100: max(100, 3 × $size) = ${shown(answer)}. Below 3000 it can burst to 3000 while it has I/O credits.",
      "Postgres and the disk > IOPS limits in the cloud"
    )
  }

  private fun panelAwait(r: Random): NumericQuestion {
    val queue = listOf(0.4, 1.2, 3.0, 8.0).random(r)
    val iops = listOf(200, 400, 1000).random(r)
    val answer = DiskMath.awaitFromQueueMs(queue, iops)
    return NumericQuestion(
      "On the host disk row, the Queue panel shows ${shown(queue)} and the IOPS panel shows $iops. What await in ms should the Await panel show?",
      answer, Tolerance.Relative(0.02), "ms",
      "Little's Law turned around: await = queue / IOPS = ${shown(queue)} / $iops = ${shown(answer / DiskMath.MILLIS_PER_SECOND)} s, so ${shown(answer)} ms.",
      "Disk panels in this lab > The host disk row"
    )
  }

  private fun panelWalSync(r: Random): NumericQuestion {
    val syncs = listOf(1200, 1800, 3000).random(r)
    val seconds = listOf(0.9, 1.8, 6.0, 15.0).random(r)
    val answer = DiskMath.meanSyncMs(seconds, syncs)
    return NumericQuestion(
      "Over one minute, pg_wal_syncs_total grew by $syncs and pg_wal_sync_seconds_total by ${shown(seconds)}. What is the mean WAL sync time in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "${shown(seconds)} s / $syncs syncs = ${shown(answer / DiskMath.MILLIS_PER_SECOND)} s, so ${shown(answer)} ms. Every commit waits about that long for its flush.",
      "Disk panels in this lab > The Postgres row"
    )
  }
}

package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/disk.md, at least one per subsection. */
internal object DiskConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Disk math
    ChoiceQuestion(
      text = "A database commits often, each commit with an fsync of a few kB. The volume allows 3000 IOPS and 125 MiB/s. Which limit does it hit first?",
      answer = "The IOPS limit: small writes run out of operations long before they move 125 MiB/s",
      others = listOf(
        "The throughput limit, because fsync forces large writes",
        "Both at the same time, because the two limits are set to match",
        "Neither: fsync writes go to the page cache and never count"
      ),
      explanation = "Throughput = IOPS × size. 3000 writes of 4 kB is under 12 MiB/s, far below 125 MiB/s. Small I/O hits the IOPS limit first, large I/O the throughput limit.",
      doc = "Disk math > IOPS, size and throughput"
    ),
    ChoiceQuestion(
      text = "iostat shows an NVMe SSD at 100% util. Await is flat and low. What do you conclude?",
      answer = "Not much yet: 100% only means at least one I/O was always in flight, and an SSD serves many at once",
      others = listOf(
        "The SSD is full and cannot take one more I/O",
        "The SSD is failing; %util above 90% is an error",
        "The app must be reading, because writes never count in %util"
      ),
      explanation = "Busy share is time-based. A device that works in parallel can be 100% busy and still take more work. Await and the queue tell you whether I/O waits.",
      doc = "Disk math > Utilization and service time"
    ),
    ChoiceQuestion(
      text = "Disk await went from 1 ms to 5 ms while IOPS stayed the same. Which other panel must have moved?",
      answer = "The queue (mean I/Os in flight): it is IOPS × await, so it grew five times",
      others = listOf(
        "Throughput, which must have grown five times",
        "None: await and the queue are independent",
        "The busy share, which must have dropped"
      ),
      explanation = "Little's Law: queue = IOPS × await. Same IOPS and five times the await means five times as many I/Os in flight. That is saturation.",
      doc = "Disk math > Await and the queue"
    ),

    // ---------------------------------------------------------------- Postgres and the disk
    ChoiceQuestion(
      text = "The disk under Postgres gets slow. /checkout p99 rises but /products (served from a cache) stays flat. Why the difference?",
      answer = "A commit waits for its WAL flush to reach the disk; a cached read never touches the disk",
      others = listOf(
        "/products uses a different Postgres server",
        "Reads have priority over writes in Postgres",
        "/checkout holds a lock on the products table"
      ),
      explanation = "Commit latency includes one WAL flush. Read-only requests do not wait for WAL, and a cache hit skips Postgres altogether.",
      doc = "Postgres and the disk > WAL and fsync at commit"
    ),
    ChoiceQuestion(
      text = "Someone sets checkpoint_completion_target from 0.9 to 0.1 and checkpoint_timeout to 30 s. What do you expect on the disk?",
      answer = "Short bursts of writes every 30 s, then quiet, instead of a slow steady trickle",
      others = listOf(
        "Less disk I/O in total, because checkpoints are smaller",
        "No change until Postgres restarts",
        "Constant high I/O with no pattern"
      ),
      explanation = "The target spreads a checkpoint's writes over that share of the interval. 0.1 of 30 s squeezes them into about 3 s. More checkpoints also mean more full page images, so more WAL. The settings apply on a reload.",
      doc = "Postgres and the disk > Checkpoints"
    ),
    ChoiceQuestion(
      text = "WAL written per second jumps right after every checkpoint, then falls back. What causes the jump?",
      answer = "Full page images: the first change to each page after a checkpoint writes the whole page to WAL",
      others = listOf(
        "The checkpoint record itself is several MB",
        "Autovacuum always starts right after a checkpoint",
        "The WAL writer flushes the buffers it skipped during the checkpoint"
      ),
      explanation = "After a checkpoint, the first change to a page logs an image of the whole page. A shorter checkpoint interval means more of them, so more WAL.",
      doc = "Postgres and the disk > Checkpoints"
    ),
    ChoiceQuestion(
      text = "You look at pg_stat_activity ten times and never see IO / WALSync, yet commits are slow. What is wrong with the check?",
      answer = "A WAL sync lasts about a millisecond: one look rarely catches it, so sample often and average",
      others = listOf(
        "WALSync only shows when fsync is off",
        "pg_stat_activity never shows IO waits",
        "Slow commits always show as Lock waits instead"
      ),
      explanation = "A wait event is a snapshot. By Little's Law, 30 syncs/s of 1 ms each means 0.03 processes waiting on average. The lab samples every 100 ms and exports the mean.",
      doc = "Postgres and the disk > Wait events"
    ),
    ChoiceQuestion(
      text = "Which wait events should you leave out when you look for a Postgres bottleneck?",
      answer = "Activity and Client: an idle process, or a session waiting for its application",
      others = listOf(
        "IO and LWLock, because they are always there",
        "Lock, because locks are the app's fault",
        "Timeout, because nothing waits on a timer"
      ),
      explanation = "Activity is an idle process in its main loop. Client is a session waiting for the app to send something. Neither is work waiting for a resource.",
      doc = "Postgres and the disk > Wait events"
    ),
    ChoiceQuestion(
      text = "Postgres waits on IO / WALSync more and more. The host disk shows low await, low busy share, and write IOPS flat at exactly 60. What fits best?",
      answer = "An IOPS limit above the device: the writes wait before they reach the disk",
      others = listOf(
        "Another process saturates the disk",
        "A slow query holds locks",
        "The disk is full"
      ),
      explanation = "A cgroup io.max limit holds I/O back before the device, so the host sees an idle disk while the database waits. A flat round number of IOPS is the sign of a quota.",
      doc = "Postgres and the disk > IOPS limits in the cloud"
    ),

    // ---------------------------------------------------------------- Disk panels in this lab
    ChoiceQuestion(
      text = "On the host disk row, which panel is the saturation of the disk?",
      answer = "Queue: the mean number of I/Os in flight",
      others = listOf(
        "IOPS by device",
        "Throughput by device",
        "Busy share"
      ),
      explanation = "Queue (aqu-sz) is work waiting for the disk. Busy share is utilization, IOPS and throughput are the work done.",
      doc = "Disk panels in this lab > The host disk row"
    ),
    ChoiceQuestion(
      text = "Mean WAL sync time rises from 1 ms to 15 ms while the host disk await stays at 1 ms. Where is the extra time?",
      answer = "Above the device: a limit such as a cgroup io.max holds the writes back before they reach the disk",
      others = listOf(
        "Inside the disk: await is not counted for fsync",
        "In the network between api and Postgres",
        "In a lock inside Postgres"
      ),
      explanation = "Both numbers rise together when the disk itself is busy. A WAL sync time that grows while the device await stays flat means the wait sits before the device.",
      doc = "Disk panels in this lab > The Postgres row"
    ),
    ChoiceQuestion(
      text = "The Checkpoint write panel shows a big value once every 5 minutes on a healthy lab. Is that a problem?",
      answer = "No: write time includes the pauses that spread the checkpoint over about 4.5 minutes",
      others = listOf(
        "Yes: the checkpoint wrote to disk for 4.5 minutes without a break",
        "Yes: it means max_wal_size is too small",
        "No, because the panel only counts failed checkpoints"
      ),
      explanation = "checkpoint_write_time covers the whole write phase, pauses included, and the counter moves when the checkpoint ends. Sync time is the flush at the end and the better sign of a busy disk.",
      doc = "Disk panels in this lab > The Postgres row"
    )
  )
}

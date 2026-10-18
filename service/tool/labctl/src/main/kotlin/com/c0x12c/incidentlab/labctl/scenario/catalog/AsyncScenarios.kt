package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MEASURED_WORKER_CAPACITY
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PARTITIONS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.SECONDS_PER_MINUTE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.WORKER_CAPACITY_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario

/** Faults on the Kafka leg: the producer, the worker and the lag between them. */
internal object AsyncScenarios {

  val all: List<Scenario> = listOf(
    consumerBacklog(),
    consumerSlow(),
    poisonMessage(),
    lagAfterFix(),
    busyPoll()
  )

  private fun consumerBacklog() = Scenario(
    id = "consumer_backlog",
    title = "The consumer cannot keep up",
    level = 2,
    topics = listOf("async", "load"),
    params = { random, _ -> mapOf("extra" to random.nextInt(170, 241)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("extra_events_rps" to p.int("extra")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the reporting team says new orders take minutes to show up (normally seconds). The worker shows no errors." },
    hints = listOf(
      "The worker's RED: consume rate, errors, processing time. Did the processing time change?",
      "Compare the produce rate with the consume rate. How is lag moving over time?",
      "Split the produce rate by source."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("worker"), signal = setOf("consumer_lag"), cause = "consumer_backlog"),
    explain = { p ->
      "A backfill job pushes about ${p.int("extra")} extra events/s into the orders topic. The worker processes each partition sequentially, " +
        "about 25 ms per message, so its capacity is about $WORKER_CAPACITY_PER_SECOND msg/s across 3 partitions. The produce rate exceeds that capacity: " +
        "processing time is flat and errors are zero, but lag in messages and in seconds grows steadily. " +
        "Lag is the consumer group's saturation; end-to-end latency is what users see."
    },
    steps = listOf(
      "Problem statement: new orders take minutes to show up, and the worker shows no errors.",
      "The worker's RED: consume rate, errors, processing time. Processing time is flat and errors are zero.",
      "Compare the produce rate with the consume rate: produce is above the consumer's capacity, so lag grows over time.",
      "Split the produce rate by source: a backfill job adds extra events.",
      "The cause: the producer sends more than the consumer can handle. Lag is the consumer group's saturation."
    ),
    evidence = listOf(
      "sum by (source) (rate(kafka_produced_total[1m]))",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "max(kafka_consumer_lag_seconds)"
    ),
    cli = listOf(
      "for i in 1 2; do curl -s http://worker:8002/metrics | grep '^kafka_consumer_lag_messages'; sleep 15; done   # lag grows on every partition",
      "for i in 1 2; do curl -s http://api:8000/metrics | grep '^kafka_produced_total.*outcome=\"ok\"'; sleep 10; done   # source=backfill grows much faster than source=checkout"
    ),
    doc = "RED at many layers > The async layer"
  )

  private fun consumerSlow() = Scenario(
    id = "consumer_slow",
    title = "The worker got slower per message",
    level = 2,
    topics = listOf("async", "layers"),
    params = { random, _ -> mapOf("process_ms" to random.nextInt(260, 331)) },
    faults = { p -> FaultSpec(services = mapOf("worker" to mapOf("process_ms" to p.int("process_ms")))) },
    marker = { "Deploy order-worker v1.9: fraud check added to each order" },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the reporting team says new orders take minutes to show up. Order volume is normal and the worker shows no errors." },
    hints = listOf(
      "The worker's RED: consume rate, errors, processing time per message. Which one moved?",
      "Compare the produce rate with the consume rate. Did the producer grow, or did the consumer shrink?",
      "Capacity = partitions / processing time. With 3 partitions, what is it now against about $CHECKOUTS_PER_SECOND msg/s coming in?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), signal = setOf("consumer_lag"), cause = "consumer_slow", partial = setOf("consumer_backlog")),
    explain = { p ->
      val capacity = PARTITIONS * MILLIS_PER_SECOND / p.int("process_ms")
      "v1.9 spends about ${p.int("process_ms")} ms per message instead of 25. Each partition runs one message at a time, so the group's capacity " +
        "dropped from about $WORKER_CAPACITY_PER_SECOND msg/s to about ${p.fixed(capacity, 0)} msg/s, below the normal $CHECKOUTS_PER_SECOND msg/s of orders. " +
        "The produce rate is flat and errors are zero, but the consume rate is pinned at capacity and lag grows on every partition. " +
        "Same symptom as the backlog exercise, opposite cause: there the producer grew, here the consumer shrank. " +
        "Processing time per message is the metric that tells them apart."
    },
    steps = listOf(
      "Problem statement: new orders take minutes, order volume is normal, and there are no errors.",
      "The worker's RED: processing time per message rose, and the consume rate is pinned at capacity.",
      "Compare produce with consume: the producer did not grow, so the consumer shrank.",
      "Capacity is partitions divided by processing time. With 3 partitions it fell below the incoming order rate.",
      "The cause: the worker got slower per message. Same symptom as the backlog, opposite cause; processing time tells them apart."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(kafka_processing_seconds_bucket[1m])))",
      "sum(rate(kafka_produced_total[1m]))",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "max(kafka_consumer_lag_seconds)"
    ),
    cli = listOf(
      "for i in 1 2; do curl -s http://worker:8002/metrics | grep -E '^kafka_processing_seconds_(sum|count)'; sleep 30; done   # (sum2 - sum1) / (count2 - count1) is the time per message, far above 0.025 s",
      "for i in 1 2; do curl -s http://api:8000/metrics | grep '^kafka_produced_total.*outcome=\"ok\"'; sleep 10; done   # only source=checkout grows: the producer did not change"
    ),
    doc = "RED at many layers > The async layer"
  )

  private fun poisonMessage() = Scenario(
    id = "poison_message",
    title = "Poison message",
    level = 3,
    topics = listOf("async"),
    params = { random, _ -> mapOf("nonce" to String.format("%06x", random.nextInt(NONCE_SPACE))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("poison_nonce" to p.string("nonce")), "worker" to mapOf("fail_nonce" to p.string("nonce")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, some orders are never processed while others are fine. The worker's total throughput is almost unchanged." },
    hints = listOf(
      "Look at lag per partition instead of the total.",
      "Are the worker's errors and retries up? On which partition?",
      "Read the worker log: make logs S=worker"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), signal = setOf("lag_per_partition"), cause = "poison_message", partial = setOf("consumer_backlog")),
    explain = { p ->
      "One bad event (currency 'XXX', nonce ${p.string("nonce")}) makes the worker fail; it retries every second and never skips the message. " +
        "Because each partition is processed in order, the whole partition holding it is blocked: exactly one partition's lag grows without end " +
        "while the others keep moving, so the total throughput barely changes. Fix: cap the retries, then send the message to a DLQ."
    },
    steps = listOf(
      "Problem statement: some orders are never processed, others are fine, and total throughput is almost unchanged.",
      "Look at lag per partition, not the total: exactly one partition's lag grows in a straight line.",
      "The worker's retries are up on that one partition only.",
      "Read the worker log: one event fails and is retried every second, never skipped.",
      "The cause: a poison message blocks its partition, because each partition is processed in order. Cap the retries and use a DLQ."
    ),
    evidence = listOf(
      "kafka_consumer_lag_messages",
      "sum by (partition) (rate(kafka_consumer_retries_total[1m]))"
    ),
    cli = listOf(
      "for i in 1 2; do curl -s http://worker:8002/metrics | grep -E '^kafka_consumer_(lag_messages|retries_total)'; sleep 15; done   # one partition: its lag and its retries grow, the others stay flat"
    ),
    doc = "RED at many layers > The async layer (poison message)"
  )

  private fun lagAfterFix() = Scenario(
    id = "lag_after_fix",
    title = "Lag stays high after the fix",
    level = 3,
    topics = listOf("async", "little"),
    params = { _, _ -> mapOf("slow_ms" to SLOW_WORKER_MS, "extra" to DRAIN_BACKFILL_RPS, "fix_after" to FIX_AFTER_SECONDS) },
    faults = { p ->
      val worker = if (p.elapsedSeconds < p.int("fix_after")) mapOf("worker" to mapOf<String, Any?>("process_ms" to p.int("slow_ms"))) else emptyMap()
      FaultSpec(services = worker + ("api" to mapOf("extra_events_rps" to p.int("extra"))))
    },
    marker = { "Deploy order-worker v1.9: fraud check added to each order" },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, orders started to show up late. The worker team found a bug in order-worker v1.9 and rolls it back about " +
        "${p.int("fix_after") / SECONDS_PER_MINUTE.toDouble()} minutes after this ticket. Wait for the rollback, then answer: is the rollback broken, " +
        "and when will orders be on time again?"
    },
    hints = listOf(
      "After the rollback, did processing time per message go back to normal?",
      "Compare the produce rate with the consume rate after the rollback. How big is the gap?",
      "Drain time = lag / (consume rate − produce rate)."
    ),
    answer = Answer(nature = setOf("load", "arch"), location = setOf("worker"), signal = setOf("consumer_lag"), cause = "slow_drain", partial = setOf("consumer_backlog", "consumer_slow")),
    steps = listOf(
      "Problem statement: orders late since v1.9, a rollback promised a few minutes after the ticket.",
      "Before the rollback: processing time per message is high and lag grows on every partition. That part is the bug.",
      "After the rollback: processing time is back to normal, so the rollback worked.",
      "But lag falls slowly. Compare produce with consume: a backfill job keeps the produce rate close to the worker's capacity.",
      "Drain time = lag / (consume − produce): a large backlog over a small gap takes many minutes.",
      "The cause: the fix worked, but the headroom is too small to drain the backlog quickly. Pause the backfill or add consumers."
    ),
    explain = { p ->
      val produce = CHECKOUTS_PER_SECOND + p.int("extra")
      val slowCapacity = PARTITIONS * MILLIS_PER_SECOND / p.int("slow_ms")
      val backlog = (produce - slowCapacity) * p.int("fix_after")
      val drain = MEASURED_WORKER_CAPACITY - produce
      "Until the rollback, v1.9 spent ${p.int("slow_ms")} ms per message, so the worker handled about ${p.fixed(slowCapacity, 0)} msg/s while about " +
        "$produce msg/s came in (orders plus a backfill job): the lag grew by about ${p.fixed(produce - slowCapacity, 0)} msg/s. After the rollback the worker " +
        "is back to about $MEASURED_WORKER_CAPACITY msg/s, but the backfill still sends ${p.int("extra")}/s, so the backlog drains at only about $drain msg/s. " +
        "A backlog of about ${p.fixed(backlog, 0)} messages takes about ${p.fixed(backlog / drain / SECONDS_PER_MINUTE, 0)} minutes to go. " +
        "The rollback is not broken: processing time is back to normal. The lag falls in a slow straight line because the headroom is small. " +
        "Fix: pause the backfill until the lag is gone, or add partitions and consumers."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(kafka_processing_seconds_bucket[1m])))",
      "sum(rate(kafka_produced_total[1m]))",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "sum(kafka_consumer_lag_messages)",
      "sum by (source) (rate(kafka_produced_total[1m]))"
    ),
    doc = "RED at many layers > The async layer; Linking RED and USE > Little's Law"
  )

  private fun busyPoll() = Scenario(
    id = "busy_poll",
    title = "The worker burns a core doing nothing",
    level = 2,
    topics = listOf("async", "cpu", "use"),
    params = { _, _ -> mapOf("poll_ms" to 0) },
    faults = { p -> FaultSpec(services = mapOf("worker" to mapOf("poll_ms" to p.int("poll_ms")))) },
    marker = { "Deploy order-worker v2.0: consumer loop rewritten without a blocking poll" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, the CPU alarm fired on worker: 100% of its limit. The on-call asks whether the queue is backing up and whether we should " +
        "add consumers before the evening peak."
    },
    hints = listOf(
      "A CPU alarm is utilization. Before adding consumers, read the worker's RED and its saturation: consume rate, processing time, lag.",
      "Lag is 0 and the consume rate is unchanged, so the worker is not working harder. What else keeps a thread at 100%?",
      "A poll with no wait returns at once when there is nothing to fetch, and the loop asks again: busy, but idle. Compare process CPU with the consume rate."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), signal = setOf("worker_cpu_vs_lag"), cause = "busy_poll"),
    explain = { p ->
      "v2.0 polls Kafka with a timeout of ${p.int("poll_ms")} ms. A poll with no wait returns at once when nothing is buffered, so the loop spins: one thread stays " +
        "on CPU and asks the broker again and again. Process CPU climbs to about one core, which is the worker's whole limit, and the cgroup starts to throttle it. " +
        "Yet the consume rate is still about $CHECKOUTS_PER_SECOND msg/s, processing time per message barely moves and lag stays near 0. " +
        "The work did not change, only the waiting did. Utilization without saturation is not a bottleneck. Adding consumers would add more spinning threads and change nothing for users. " +
        "Fix: poll with a timeout again (the old $DEFAULT_POLL_MS ms), so the thread blocks while the topic is empty."
    },
    steps = listOf(
      "Problem statement: a CPU alarm on worker at 100% of its limit, and a request to add consumers.",
      "USE on the worker in order: errors 0, saturation (lag) 0, utilization 100%. Utilization alone is the finding.",
      "RED on the worker: consume rate and processing time per message are unchanged. The worker does the same work as before.",
      "Compare process CPU with the consume rate: a whole core for 15 msg/s is not processing. Something spins.",
      "The deploy marker points at the consumer loop: a poll with no wait returns empty and runs again. That is busy polling.",
      "The cause: a hot loop, not a backlog. More consumers would spin too. Put the blocking poll back."
    ),
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"worker\"}[1m])",
      "sum(kafka_consumer_lag_messages)",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "rate(cgroup_cpu_throttled_periods_total{job=\"worker\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"worker\"}[1m])"
    ),
    cli = listOf(
      "top -H -b -n 1 -p $(pid worker) | head -12   # one thread near 100% CPU: the Kafka poll loop spins",
      "cat $(cg $(pid worker))/cpu.stat   # nr_throttled climbs: the worker uses its whole 1-core limit",
      "curl -s http://worker:8002/metrics | grep ^kafka_consumer_lag_messages   # lag near 0: there is no work waiting"
    ),
    doc = "CPU busy with no work > Busy polling"
  )

  private const val NONCE_SPACE = 0x1000000
  private const val DEFAULT_POLL_MS = 500
  private const val SLOW_WORKER_MS = 100
  private const val DRAIN_BACKFILL_RPS = 75
  private const val FIX_AFTER_SECONDS = 150
}

package com.c0x12c.incidentlab.worker.orders.loop

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.checkout.api.OrderRepository
import com.c0x12c.incidentlab.client.dto.checkout.OrderEvent
import com.c0x12c.incidentlab.database.DatabaseContext
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.infra.config.AppKafkaConfig
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabGauge
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.Propagation
import com.c0x12c.incidentlab.tracing.markError
import com.c0x12c.incidentlab.tracing.span
import com.c0x12c.incidentlab.utility.jitter
import com.c0x12c.incidentlab.utility.retryForever
import com.c0x12c.incidentlab.worker.orders.factory.OrdersFaults
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer

class PoisonMessageException(message: String) : RuntimeException(message)

/**
 * The consumer runs for the life of the process. One coroutine polls the broker and hands records
 * to a bounded channel per partition; one coroutine per partition drains its channel in order. A
 * record that fails is retried every second on the same partition until it succeeds, which is the
 * poison-message exercise: that partition's lag grows while the others keep moving. Offsets are
 * committed once a second from the poll loop, because the consumer is only ever touched there.
 */
@Singleton
class OrdersConsumerLoop(
  private val config: AppKafkaConfig,
  private val faults: Faults,
  private val db: DatabaseContext,
  private val orders: OrderRepository,
  private val openTelemetry: OpenTelemetry,
  private val tracer: Tracer,
  private val objectMapper: ObjectMapper,
  registry: MeterRegistry
) : ApplicationEventListener<StartupEvent>, Logging {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("orders-consumer"))

  private val consumed = LabCounter(registry, "kafka_consumed", "Messages handled", listOf("source", "outcome"))
  private val processing = LabHistogram(registry, "kafka_processing_seconds", "Processing time per message (one attempt)", LatencyBuckets.LATENCY)
  private val endToEnd = LabHistogram(registry, "kafka_end_to_end_seconds", "From event creation to processed", LatencyBuckets.END_TO_END)
  private val lagMessages = LabGauge(registry, "kafka_consumer_lag_messages", "Messages not yet processed", listOf("partition"))
  private val lagSeconds = LabGauge(registry, "kafka_consumer_lag_seconds", "Age of the oldest unprocessed message", listOf("partition"))
  private val queued = LabGauge(registry, "kafka_partition_queue", "Fetched messages waiting in memory", listOf("partition"))
  private val retries = LabCounter(registry, "kafka_consumer_retries", "Processing retries", listOf("partition"))

  private val queues = ConcurrentHashMap<TopicPartition, Channel<ConsumerRecord<String, String>>>()
  private val queueSizes = ConcurrentHashMap<TopicPartition, AtomicInteger>()
  private val nextOffset = ConcurrentHashMap<TopicPartition, Long>()
  private val headTimestamp = ConcurrentHashMap<TopicPartition, Long>()
  private val paused = mutableSetOf<TopicPartition>()

  override fun onApplicationEvent(event: StartupEvent) {
    scope.launch { pollLoop() }
  }

  @PreDestroy
  fun stop() {
    scope.cancel()
  }

  private suspend fun pollLoop() {
    retryForever("kafka admin") { ensureTopic() }
    val consumer = retryForever("kafka consumer") { KafkaConsumer<String, String>(consumerProperties()).also { it.subscribe(listOf(config.topic)) } }
    info { "consumer ready [topic=${config.topic}, group=$GROUP_ID]" }
    var lastCommit = System.nanoTime()
    var lastLag = System.nanoTime()
    val committed = mutableMapOf<TopicPartition, Long>()
    try {
      while (scope.isActive) {
        val records = runInterruptible { consumer.poll(Duration.ofMillis(faults.long(OrdersFaults.POLL_MS).coerceAtLeast(0))) }
        for (partition in records.partitions()) {
          val queue = queues.computeIfAbsent(partition) { newPartition(it) }
          for (record in records.records(partition)) {
            queue.send(record)
            queueSizes.getValue(partition).incrementAndGet()
          }
          if (queueSize(partition) > PAUSE_ABOVE && partition !in paused) {
            consumer.pause(listOf(partition))
            paused.add(partition)
          }
        }
        for (partition in paused.toList()) {
          if (queueSize(partition) < RESUME_BELOW) {
            consumer.resume(listOf(partition))
            paused.remove(partition)
          }
        }
        val now = System.nanoTime()
        if (now - lastCommit > COMMIT_INTERVAL_NANOS) {
          lastCommit = now
          commit(consumer, committed)
        }
        if (now - lastLag > LAG_INTERVAL_NANOS) {
          lastLag = now
          measureLag(consumer)
        }
      }
    } finally {
      consumer.close()
    }
  }

  private fun newPartition(partition: TopicPartition): Channel<ConsumerRecord<String, String>> {
    val queue = Channel<ConsumerRecord<String, String>>(Channel.UNLIMITED)
    queueSizes[partition] = AtomicInteger()
    scope.launch(CoroutineName("partition-${partition.partition()}")) { partitionWorker(partition, queue) }
    return queue
  }

  private suspend fun partitionWorker(partition: TopicPartition, queue: Channel<ConsumerRecord<String, String>>) {
    for (record in queue) {
      queueSizes.getValue(partition).decrementAndGet()
      headTimestamp[partition] = record.timestamp()
      var source = "unknown"
      while (true) {
        try {
          val event = process(record)
          source = event.source
          consumed.increment(source, "ok")
          break
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          consumed.increment(source, "error")
          retries.increment(partition.partition().toString())
          warn { "p${partition.partition()} offset ${record.offset()} failed, retrying: ${e.message}" }
          delay(RETRY_DELAY_MILLIS)
        }
      }
      nextOffset[partition] = record.offset() + 1
      if (queueSize(partition) == 0) {
        headTimestamp.remove(partition)
      }
    }
  }

  private suspend fun process(record: ConsumerRecord<String, String>): OrderEvent {
    val start = System.nanoTime()
    val parent = Propagation.extractKafka(openTelemetry, record.headers())
    val attributes = mapOf<String, Number>("messaging.kafka.partition" to record.partition(), "messaging.kafka.offset" to record.offset())
    val span = tracer.spanBuilder("kafka.consume ${config.topic}").setSpanKind(SpanKind.CONSUMER).setParent(parent).also { builder ->
      attributes.forEach { (key, value) -> builder.setAttribute(key, value.toLong()) }
    }.startSpan()
    try {
      val event = objectMapper.readValue(record.value(), OrderEvent::class.java)
      val poison = event.poison
      if (!poison.isNullOrBlank() && poison == faults.string(OrdersFaults.FAIL_NONCE)) {
        span.markError("unsupported currency ${event.currency}")
        throw PoisonMessageException("order ${event.orderId}: unsupported currency ${event.currency}")
      }
      delay(jitter(faults.double(OrdersFaults.PROCESS_MS), PROCESS_JITTER).toMillis())
      if (event.source == "checkout" && event.orderId > 0) {
        runInterruptible {
          span.makeCurrent().use {
            db.withConnection { connection -> orders.markProcessed(connection, event.orderId) }
          }
        }
      }
      span.makeCurrent().use {
        endToEnd.observe(((System.currentTimeMillis() - event.createdMs) / MILLIS_PER_SECOND).coerceAtLeast(0.0))
      }
      return event
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      span.recordException(e)
      throw e
    } finally {
      span.makeCurrent().use { processing.observe((System.nanoTime() - start) / NANOS_PER_SECOND) }
      span.end()
    }
  }

  private fun commit(consumer: KafkaConsumer<String, String>, committed: MutableMap<TopicPartition, Long>) {
    val todo = nextOffset.filter { (partition, offset) -> committed[partition] != offset }
    if (todo.isEmpty()) {
      return
    }
    try {
      consumer.commitSync(todo.mapValues { OffsetAndMetadata(it.value) })
      committed.putAll(todo)
    } catch (e: Exception) {
      warn { "commit failed: ${e.message}" }
    }
  }

  private fun measureLag(consumer: KafkaConsumer<String, String>) {
    try {
      val assigned = consumer.assignment()
      if (assigned.isEmpty()) {
        return
      }
      val ends = consumer.endOffsets(assigned)
      val now = System.currentTimeMillis()
      for (partition in assigned) {
        val end = ends[partition] ?: continue
        val next = nextOffset[partition] ?: consumer.committed(setOf(partition))[partition]?.offset() ?: end
        val lag = (end - next).coerceAtLeast(0)
        val label = partition.partition().toString()
        lagMessages.set(lag.toDouble(), label)
        val head = headTimestamp[partition]
        lagSeconds.set(if (lag > 0 && head != null) ((now - head) / MILLIS_PER_SECOND).coerceAtLeast(0.0) else 0.0, label)
        queued.set(queueSize(partition), label)
      }
    } catch (e: Exception) {
      warn { "lag check failed: ${e.message}" }
    }
  }

  private fun queueSize(partition: TopicPartition): Int = queueSizes[partition]?.get() ?: 0

  private fun ensureTopic() {
    AdminClient.create(Properties().apply { put("bootstrap.servers", config.bootstrap) }).use { admin ->
      try {
        admin.createTopics(listOf(NewTopic(config.topic, config.partitions, REPLICATION.toShort()))).all().get()
      } catch (e: java.util.concurrent.ExecutionException) {
        if (e.cause?.javaClass?.simpleName != "TopicExistsException") {
          throw e
        }
      }
    }
  }

  private fun consumerProperties(): Properties =
    Properties().apply {
      put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap)
      put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID)
      put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
      put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
      put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
      put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS)
      put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS)
    }

  private companion object {
    const val GROUP_ID = "order-worker"
    const val REPLICATION = 1
    const val MAX_POLL_RECORDS = 1_000
    const val FETCH_MAX_WAIT_MS = 200
    const val PAUSE_ABOVE = 2_000
    const val RESUME_BELOW = 500
    const val COMMIT_INTERVAL_NANOS = 1_000_000_000L
    const val LAG_INTERVAL_NANOS = 2_000_000_000L
    const val RETRY_DELAY_MILLIS = 1_000L
    const val PROCESS_JITTER = 0.2
    const val NANOS_PER_SECOND = 1_000_000_000.0
    const val MILLIS_PER_SECOND = 1_000.0
  }
}

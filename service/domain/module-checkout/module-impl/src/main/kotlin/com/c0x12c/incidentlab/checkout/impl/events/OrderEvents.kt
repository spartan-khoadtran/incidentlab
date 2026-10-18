package com.c0x12c.incidentlab.checkout.impl.events

import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import com.c0x12c.incidentlab.client.dto.checkout.OrderEvent
import com.c0x12c.incidentlab.infra.config.AppKafkaConfig
import com.c0x12c.incidentlab.metrics.LabCounter
import com.c0x12c.incidentlab.metrics.LabHistogram
import com.c0x12c.incidentlab.metrics.LatencyBuckets
import com.c0x12c.incidentlab.tracing.Propagation
import com.c0x12c.incidentlab.tracing.span
import com.c0x12c.incidentlab.utility.retryForever
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.util.Properties
import java.util.concurrent.Future
import kotlin.concurrent.thread
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer

/**
 * The order events the api produces. The producer connects in the background and retries until the
 * broker answers, so the api serves products while Kafka is still starting; a checkout in that
 * window counts its event as `unavailable` instead of failing the order.
 */
class OrderEvents(
  private val config: AppKafkaConfig,
  private val openTelemetry: OpenTelemetry,
  private val tracer: Tracer,
  private val objectMapper: ObjectMapper,
  registry: MeterRegistry
) : AutoCloseable, Logging {

  @Volatile
  private var producer: KafkaProducer<String, String>? = null

  private val produced = LabCounter(registry, "kafka_produced", "Events sent to Kafka", listOf("source", "outcome"))
  private val produceDuration = LabHistogram(registry, "kafka_produce_duration_seconds", "Time to get an ack from Kafka", LatencyBuckets.LATENCY)

  val topic: String
    get() = config.topic

  fun start() {
    thread(name = "kafka-producer-connect", isDaemon = true) {
      producer = retryForever("kafka") {
        KafkaProducer<String, String>(properties()).also { it.partitionsFor(config.topic) }
      }
      info { "kafka producer ready [topic=${config.topic}]" }
    }
  }

  /** Produces [event] and waits for the broker's ack, inside a PRODUCER span that carries the trace into the message headers. */
  fun produce(event: OrderEvent, source: String, key: String) {
    val current = producer
    if (current == null) {
      produced.increment(source, "unavailable")
      return
    }
    val start = System.nanoTime()
    tracer.span("kafka.produce ${config.topic}", SpanKind.PRODUCER) {
      val record = ProducerRecord(config.topic, key, objectMapper.writeValueAsString(event))
      Propagation.injectKafka(openTelemetry, record.headers())
      try {
        current.send(record).get()
        produced.increment(source, "ok")
      } catch (e: Exception) {
        produced.increment(source, "error")
        warn { "kafka produce failed: ${e.message}" }
      } finally {
        produceDuration.observe((System.nanoTime() - start) / NANOS_PER_SECOND)
      }
    }
  }

  /** Fire-and-forget send for the backfill job; the callback counts the outcome under [source]. */
  fun sendAsync(event: OrderEvent, source: String, key: String): Future<RecordMetadata>? {
    val current = producer ?: return null
    val record = ProducerRecord(config.topic, key, objectMapper.writeValueAsString(event))
    return current.send(record) { _, exception ->
      produced.increment(source, if (exception == null) "ok" else "error")
    }
  }

  override fun close() {
    producer?.close()
  }

  private fun properties(): Properties =
    Properties().apply {
      put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap)
      put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      put(ProducerConfig.ACKS_CONFIG, "1")
      put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS)
      put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS)
      put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS)
      put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS)
    }

  private companion object {
    const val LINGER_MS = 5
    const val REQUEST_TIMEOUT_MS = 5_000
    const val DELIVERY_TIMEOUT_MS = 10_000
    const val MAX_BLOCK_MS = 5_000
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

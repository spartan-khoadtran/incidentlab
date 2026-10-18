package com.c0x12c.incidentlab.tracing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import org.apache.kafka.common.header.Headers

/** W3C trace context in and out of the carriers the lab uses: HTTP header maps and Kafka record headers. */
object Propagation {

  fun inject(openTelemetry: OpenTelemetry, headers: MutableMap<String, String>, context: Context = Context.current()) {
    openTelemetry.propagators.textMapPropagator.inject(context, headers) { carrier, key, value -> carrier?.put(key, value) }
  }

  fun injectKafka(openTelemetry: OpenTelemetry, headers: Headers, context: Context = Context.current()) {
    openTelemetry.propagators.textMapPropagator.inject(context, headers) { carrier, key, value ->
      carrier?.add(key, value.toByteArray(Charsets.UTF_8))
    }
  }

  fun extractKafka(openTelemetry: OpenTelemetry, headers: Headers): Context =
    openTelemetry.propagators.textMapPropagator.extract(Context.current(), headers, KafkaHeadersGetter)

  private object KafkaHeadersGetter : TextMapGetter<Headers> {
    override fun keys(carrier: Headers): Iterable<String> = carrier.map { it.key() }

    override fun get(carrier: Headers?, key: String): String? =
      carrier?.lastHeader(key)?.value()?.toString(Charsets.UTF_8)
  }
}

package com.c0x12c.incidentlab.checkout.impl.cache

import com.c0x12c.incidentlab.tracing.span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.time.Duration
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec

/** The product page cache in Redis. Keys carry the TTL, so a changed cache config starts cold. */
class ProductCache(
  private val redisson: RedissonClient,
  private val tracer: Tracer
) {

  fun get(key: String): String? =
    tracer.span("redis GET", SpanKind.CLIENT, mapOf("db.system" to "redis")) {
      redisson.getBucket<String>(key, StringCodec.INSTANCE).get()
    }

  fun set(key: String, value: String, ttl: Duration) {
    tracer.span("redis SET", SpanKind.CLIENT, mapOf("db.system" to "redis")) {
      redisson.getBucket<String>(key, StringCodec.INSTANCE).set(value, ttl)
    }
  }
}

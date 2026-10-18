package com.c0x12c.incidentlab.tracing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Copies the current trace context onto every outgoing request. Only valid for synchronous calls:
 * an application interceptor runs on the calling thread for `execute()`, where the context is
 * current, but on a dispatcher thread for `enqueue()`, where it is not.
 */
class TraceContextInterceptor(private val openTelemetry: OpenTelemetry) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val builder: Request.Builder = chain.request().newBuilder()
    openTelemetry.propagators.textMapPropagator.inject(Context.current(), builder) { carrier, key, value ->
      carrier?.header(key, value)
    }
    return chain.proceed(builder.build())
  }
}

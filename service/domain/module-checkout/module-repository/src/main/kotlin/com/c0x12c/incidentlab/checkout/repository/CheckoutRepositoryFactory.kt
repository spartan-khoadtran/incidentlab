package com.c0x12c.incidentlab.checkout.repository

import com.c0x12c.incidentlab.checkout.api.OrderRepository
import com.c0x12c.incidentlab.checkout.api.ProductRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.annotation.Factory
import io.opentelemetry.api.trace.Tracer
import jakarta.inject.Singleton

// Publishes the JDBC repositories as their module-api interface types. @Factory methods construct
// the plain classes; the classes themselves are never annotated @Singleton.
@Factory
class CheckoutRepositoryFactory {

  @Singleton
  fun queryInstrumentation(registry: MeterRegistry, tracer: Tracer): QueryInstrumentation = QueryInstrumentation(registry, tracer)

  @Singleton
  fun productRepository(instrumentation: QueryInstrumentation): ProductRepository = DefaultProductRepository(instrumentation)

  @Singleton
  fun orderRepository(instrumentation: QueryInstrumentation): OrderRepository = DefaultOrderRepository(instrumentation)
}

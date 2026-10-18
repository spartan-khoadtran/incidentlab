package com.c0x12c.incidentlab.checkout.impl.http

import com.c0x12c.incidentlab.checkout.impl.resource.ApiGates
import io.micronaut.core.order.Ordered
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ServerFilter
import io.micronaut.http.filter.FilterContinuation
import io.micronaut.http.filter.ServerFilterPhase
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

/**
 * The concurrency limit in front of the business routes. A request past the limit waits here,
 * before the RED filter starts its clock, so the api's own duration never shows this queue: only
 * the load balancer's does. That gap is what the queue-before-app exercise is about.
 */
@ServerFilter(patterns = ["/products/**", "/checkout", "/checkout/**"])
@ExecuteOn(TaskExecutors.BLOCKING)
class AdmissionFilter(
  private val gates: ApiGates
) : Ordered {

  override fun getOrder(): Int = ServerFilterPhase.FIRST.order()

  @RequestFilter
  fun filter(request: HttpRequest<*>, continuation: FilterContinuation<MutableHttpResponse<*>>): MutableHttpResponse<*> {
    val start = System.nanoTime()
    gates.admission.acquire()
    gates.admissionWait.observe((System.nanoTime() - start) / NANOS_PER_SECOND)
    try {
      return continuation.proceed()
    } finally {
      gates.admission.release()
    }
  }

  private companion object {
    const val NANOS_PER_SECOND = 1_000_000_000.0
  }
}

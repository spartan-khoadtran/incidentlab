package com.c0x12c.incidentlab.checkout.impl.render

import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.time.Duration

/**
 * The v2.42 receipt renderer: it serialises the response with Jackson, parses it back and serialises
 * it again, in a loop, until its budget is spent on the calling thread. A CPU profile of the service
 * shows this class as the hot frame, with Jackson's serializer under it.
 */
class ReceiptRenderer(
  private val objectMapper: ObjectMapper,
  private val threads: ThreadMXBean = ManagementFactory.getThreadMXBean()
) {

  /** Renders [payload] again and again for about [budget] and returns the last rendering. */
  fun render(payload: Any, budget: Duration): String {
    val now = clock()
    val end = now() + budget.toNanos()
    var rendered: String
    do {
      rendered = objectMapper.writeValueAsString(objectMapper.readTree(objectMapper.writeValueAsString(payload)))
    } while (now() < end)
    return rendered
  }

  // The JDK reports no CPU time for a virtual thread (-1). A virtual thread is never preempted while
  // it computes, so there wall time is close to CPU time.
  private fun clock(): () -> Long {
    val cpuTime = if (threads.isCurrentThreadCpuTimeSupported) threads.currentThreadCpuTime else UNSUPPORTED
    return if (cpuTime >= 0) threads::getCurrentThreadCpuTime else System::nanoTime
  }

  private companion object {
    const val UNSUPPORTED = -1L
  }
}

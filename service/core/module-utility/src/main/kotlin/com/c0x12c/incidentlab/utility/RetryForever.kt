package com.c0x12c.incidentlab.utility

import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import java.time.Duration

/** Calls [make] until it succeeds. Under compose the dependencies start in any order. */
fun <T> Logging.retryForever(what: String, delay: Duration = Duration.ofSeconds(2), make: () -> T): T {
  while (true) {
    try {
      return make()
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      warn { "waiting for $what: ${e::class.java.simpleName}: ${e.message}" }
      Thread.sleep(delay.toMillis())
    }
  }
}

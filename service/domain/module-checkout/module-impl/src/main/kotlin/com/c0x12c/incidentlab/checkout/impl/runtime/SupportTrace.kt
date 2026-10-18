package com.c0x12c.incidentlab.checkout.impl.runtime

import com.c0x12c.logging.Logging
import com.c0x12c.logging.debug

/**
 * The verbose-logging fault: DEBUG lines per request through the console appender, the way a log
 * level raised for a support case would. api's logback.xml sets this logger to DEBUG, so every line
 * is written: one flushed write to stdout per line, under the appender's lock.
 */
class SupportTrace : Logging {

  fun write(lines: Int, route: String, key: Any) {
    for (step in 1..lines) {
      debug { "support trace route=$route key=$key step=$step/$lines" }
    }
  }
}

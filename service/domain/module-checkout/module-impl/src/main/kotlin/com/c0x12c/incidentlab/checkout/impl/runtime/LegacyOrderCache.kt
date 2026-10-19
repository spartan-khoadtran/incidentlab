package com.c0x12c.incidentlab.checkout.impl.runtime

import java.time.Duration

/**
 * The pinning fault: old code that guards the order write and a blocking cache put with
 * `synchronized` on a monitor each request makes for itself. Nobody else waits for that monitor,
 * but on JDK 21 a virtual thread that blocks inside `synchronized` keeps its carrier thread, so no
 * more of these sections run at once than the scheduler has carriers.
 */
class LegacyOrderCache {

  fun <T> locked(block: () -> T): T = synchronized(Any()) { block() }

  fun put(hold: Duration) {
    if (!hold.isZero) {
      Thread.sleep(hold)
    }
  }
}

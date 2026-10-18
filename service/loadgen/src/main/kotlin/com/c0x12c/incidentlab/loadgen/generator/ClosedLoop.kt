package com.c0x12c.incidentlab.loadgen.generator

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A fixed number of clients, each sending its next request as soon as the last one answered, with no
 * think time. A client that is no longer wanted finishes its request in flight and stops, so every
 * request it started is still counted.
 */
class ClosedLoop(
  private val scope: CoroutineScope,
  private val request: suspend () -> Unit
) {

  @Volatile
  private var wanted = 0

  private val clients = ConcurrentHashMap<Int, Job>()

  val running: Int
    get() = clients.size

  fun resize(target: Int) {
    wanted = target.coerceAtLeast(0)
    for (index in 0 until wanted) {
      clients.computeIfAbsent(index) { launchClient(index) }
    }
  }

  private fun launchClient(index: Int): Job =
    scope.launch {
      try {
        while (isActive && index < wanted) {
          request()
        }
      } finally {
        clients.remove(index)
      }
    }
}

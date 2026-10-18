package com.c0x12c.incidentlab.utility

import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A FIFO permit gate whose limit can change while callers wait. It stands in for the two software
 * resources the api exercises drive into saturation: the admission queue in front of the handlers
 * and the DB connection pool. [inFlight] and [waiting] are what the USE panels read.
 *
 * Each waiter blocks on its own future, so on virtual threads a full gate costs memory, not carrier
 * threads. A permit granted after the caller already gave up is handed straight back.
 */
class ResizableGate(private val limit: () -> Int) {

  private val lock = ReentrantLock()
  private val waiters = ArrayDeque<CompletableFuture<Unit>>()

  @Volatile
  var inFlight: Int = 0
    private set

  val waiting: Int
    get() = lock.withLock { waiters.size }

  /** Returns false once [timeout] passes without a permit; a null timeout waits for ever. */
  fun acquire(timeout: Duration? = null): Boolean {
    val ticket = lock.withLock {
      if (inFlight < limit() && waiters.isEmpty()) {
        inFlight += 1
        return true
      }
      CompletableFuture<Unit>().also { waiters.addLast(it) }
    }
    try {
      if (timeout == null) {
        ticket.get()
      } else {
        ticket.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
      }
      return true
    } catch (e: TimeoutException) {
      giveUp(ticket)
      return false
    } catch (e: InterruptedException) {
      giveUp(ticket)
      Thread.currentThread().interrupt()
      return false
    }
  }

  fun release() {
    lock.withLock {
      inFlight -= 1
      wake()
    }
  }

  /** Hands out permits a raised limit now allows; a lowered limit takes effect as permits come back. */
  fun refresh() {
    lock.withLock { wake() }
  }

  private fun giveUp(ticket: CompletableFuture<Unit>) {
    lock.withLock {
      if (!waiters.remove(ticket)) {
        // The permit arrived between the timeout and this lock: it is ours, so give it back.
        inFlight -= 1
        wake()
      }
    }
  }

  private fun wake() {
    while (waiters.isNotEmpty() && inFlight < limit()) {
      val next = waiters.pollFirst()
      inFlight += 1
      next.complete(Unit)
    }
  }
}

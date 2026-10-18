package com.c0x12c.incidentlab.utility

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test

class ResizableGateTest {

  @Test
  fun `grants permits up to the limit and counts the rest as waiting`() {
    val gate = ResizableGate { 2 }

    assertThat(gate.acquire(Duration.ZERO)).isTrue()
    assertThat(gate.acquire(Duration.ZERO)).isTrue()
    assertThat(gate.acquire(Duration.ofMillis(20))).isFalse()
    assertThat(gate.inFlight).isEqualTo(2)
    assertThat(gate.waiting).isEqualTo(0)
  }

  @Test
  fun `a timed out waiter leaves nothing behind`() {
    val gate = ResizableGate { 1 }
    gate.acquire()

    assertThat(gate.acquire(Duration.ofMillis(10))).isFalse()

    gate.release()
    assertThat(gate.inFlight).isEqualTo(0)
    assertThat(gate.acquire(Duration.ZERO)).isTrue()
  }

  @Test
  fun `releasing hands the permit to the oldest waiter`() {
    val gate = ResizableGate { 1 }
    gate.acquire()
    val order = mutableListOf<String>()
    val started = CountDownLatch(2)
    val first = thread {
      started.countDown()
      gate.acquire()
      synchronized(order) { order.add("first") }
    }
    Thread.sleep(50)
    val second = thread {
      started.countDown()
      gate.acquire()
      synchronized(order) { order.add("second") }
    }
    started.await(1, TimeUnit.SECONDS)
    Thread.sleep(50)
    assertThat(gate.waiting).isEqualTo(2)

    gate.release()
    first.join(1_000)
    gate.release()
    second.join(1_000)

    assertThat(order).isEqualTo(listOf("first", "second"))
  }

  @Test
  fun `a raised limit releases waiters on refresh`() {
    var limit = 1
    val gate = ResizableGate { limit }
    gate.acquire()
    val waiter = thread { gate.acquire() }
    Thread.sleep(50)
    assertThat(gate.waiting).isEqualTo(1)

    limit = 2
    gate.refresh()
    waiter.join(1_000)

    assertThat(gate.waiting).isEqualTo(0)
    assertThat(gate.inFlight).isEqualTo(2)
  }
}

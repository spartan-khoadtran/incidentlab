package com.c0x12c.incidentlab.loadgen.generator

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import com.c0x12c.incidentlab.faults.Faults
import com.c0x12c.incidentlab.loadgen.factory.LoadgenConfig
import com.c0x12c.incidentlab.loadgen.factory.LoadgenFaults
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** loadgen against a fake target that answers every request after a fixed time and counts how many it holds at once. */
class LoadGeneratorTest {

  private val holding = AtomicInteger()
  private val peak = AtomicInteger()
  private val served = AtomicInteger()

  private val target: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), BACKLOG).apply {
    executor = Executors.newCachedThreadPool()
    createContext("/") { exchange ->
      peak.accumulateAndGet(holding.incrementAndGet(), ::maxOf)
      exchange.requestBody.readAllBytes()
      Thread.sleep(SERVICE_MS)
      holding.decrementAndGet()
      served.incrementAndGet()
      val body = "{}".toByteArray()
      exchange.sendResponseHeaders(OK, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    start()
  }

  private val faults = Faults(LoadgenFaults.DEFAULTS)
  private val registry = SimpleMeterRegistry()
  private val openTelemetry = OpenTelemetry.noop()
  private val generator = LoadGenerator(
    LoadgenConfig(targetUrl = "http://127.0.0.1:${target.address.port}", startDelay = Duration.ZERO),
    faults,
    openTelemetry,
    openTelemetry.getTracer("test"),
    registry
  )

  @AfterEach
  fun stop() {
    generator.stop()
    target.stop(0)
  }

  @Test
  fun `closed clients replace the open arrivals and never hold more than one request each`() {
    faults.replace(mapOf(LoadgenFaults.CLOSED_CLIENTS to CLIENTS, LoadgenFaults.TENANTS to mapOf("web" to OPEN_RPS_IGNORED)))
    generator.start()

    Thread.sleep(RUN_MS)

    val count = served.get()
    assertThat(peak.get()).isEqualTo(CLIENTS)
    val ceiling = (CLIENTS * RUN_MS / SERVICE_MS).toInt()
    assertThat(count).isBetween(ceiling / 3, ceiling)
    assertThat(registry.get("client_closed_clients").gauge().value()).isEqualTo(CLIENTS.toDouble())
    assertThat(registry.get("client_target_rps").gauge().value()).isEqualTo(0.0)
    assertThat(registry.find("client_requests").counters().sumOf { it.count() }).isGreaterThan(count - CLIENTS - 1.0)
  }

  @Test
  fun `fewer clients finish what they started and then hold fewer requests, and zero brings the open arrivals back`() {
    faults.replace(mapOf(LoadgenFaults.CLOSED_CLIENTS to CLIENTS, LoadgenFaults.TENANTS to mapOf("web" to 0)))
    generator.start()
    Thread.sleep(SETTLE_MS)

    faults.replace(mapOf(LoadgenFaults.CLOSED_CLIENTS to 1, LoadgenFaults.TENANTS to mapOf("web" to 0)))
    Thread.sleep(SETTLE_MS)
    peak.set(holding.get())
    Thread.sleep(SETTLE_MS)
    assertThat(peak.get()).isLessThanOrEqualTo(1)

    faults.replace(mapOf(LoadgenFaults.TENANTS to mapOf("web" to OPEN_RPS)))
    Thread.sleep(SETTLE_MS)
    val before = served.get()
    Thread.sleep(RUN_MS)
    assertThat(registry.get("client_closed_clients").gauge().value()).isEqualTo(0.0)
    assertThat(served.get() - before).isGreaterThan((OPEN_RPS * RUN_MS / MILLIS_PER_SECOND / 3).toInt())
  }

  private companion object {
    const val CLIENTS = 4
    const val SERVICE_MS = 20L
    const val RUN_MS = 1_500L
    const val SETTLE_MS = 300L
    const val OPEN_RPS = 40
    const val OPEN_RPS_IGNORED = 1_000
    const val MILLIS_PER_SECOND = 1_000L
    const val BACKLOG = 64
    const val OK = 200
  }
}

package com.c0x12c.incidentlab.checkout.impl.pricing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.c0x12c.incidentlab.checkout.impl.CheckoutFaults
import com.c0x12c.incidentlab.faults.Faults
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KeepAliveSwitchTest {

  /** A keep-alive HTTP/1.1 server that records, per accepted connection, whether the client closed it first. */
  private class Server(expected: Int) : AutoCloseable {
    private val socket = ServerSocket(0)
    val port: Int = socket.localPort
    val endings: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val done = CountDownLatch(expected)

    init {
      thread(isDaemon = true) {
        while (!socket.isClosed) {
          val connection = runCatching { socket.accept() }.getOrNull() ?: break
          thread(isDaemon = true) {
            connection.use {
              it.soTimeout = IDLE_MILLIS
              val input = it.getInputStream()
              val output = it.getOutputStream()
              val ending = try {
                while (readRequest(input)) {
                  output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                  output.flush()
                }
                "client closed"
              } catch (e: SocketTimeoutException) {
                "kept open"
              }
              endings += ending
              done.countDown()
            }
          }
        }
      }
    }

    fun await() {
      done.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    override fun close() {
      socket.close()
    }

    /** False on EOF before a request starts. */
    private fun readRequest(input: InputStream): Boolean {
      var tail = 0
      while (true) {
        val b = input.read()
        if (b < 0) {
          return false
        }
        tail = (tail shl 8) or b
        if (tail == CRLF_CRLF) {
          return true
        }
      }
    }
  }

  private val registry = SimpleMeterRegistry()
  private val faults = Faults(CheckoutFaults.DEFAULTS)
  private val pooled = OkHttpClient.Builder().eventListener(ConnectionCounter(registry, "pricing")).build()
  private var server: Server? = null

  @AfterEach
  fun stop() {
    server?.close()
  }

  @Test
  fun `with keep-alive two calls share one connection that stays open`() {
    val server = Server(expected = 1).also { this.server = it }

    callTwice(server)
    server.await()

    assertThat(opened()).isEqualTo(1.0)
    assertThat(server.endings.toList()).containsExactly("kept open")
  }

  @Test
  fun `without keep-alive every call opens a connection and the client closes it first`() {
    faults.replace(mapOf(CheckoutFaults.PRICING_KEEPALIVE to false))
    val server = Server(expected = 2).also { this.server = it }

    callTwice(server)
    server.await()

    assertThat(opened()).isEqualTo(2.0)
    assertThat(server.endings.toList()).containsExactly("client closed", "client closed")
  }

  private fun callTwice(server: Server) {
    val switch = KeepAliveSwitch(faults, pooled)
    repeat(2) {
      switch.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/price").build()).execute().use { response ->
        assertThat(response.body.string()).isEqualTo("ok")
      }
    }
  }

  private fun opened(): Double = registry.get("http_client_connections").tag("target", "pricing").counter().count()

  private companion object {
    const val IDLE_MILLIS = 1_500
    const val AWAIT_SECONDS = 5L
    const val CRLF_CRLF = 0x0D0A0D0A
  }
}

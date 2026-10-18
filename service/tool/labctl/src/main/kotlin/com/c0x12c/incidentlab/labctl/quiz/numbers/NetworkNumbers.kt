package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.math.pow
import kotlin.random.Random

/**
 * Numeric drills for docs/network.md. Each generator draws from a short list of values and hands them
 * to a formula function, so a test can check the formula on fixed values.
 */
internal object NetworkNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    { r -> bandwidthDelay(GBPS.random(r), RTT_MS.random(r)) },
    { r -> windowNeeded(MBPS.random(r), RTT_MS.random(r)) },
    { r -> windowThroughput(WINDOW_KIB.random(r), RTT_MS.random(r)) },
    { r -> roundTripsPool(RPS.random(r), HOLD_MS.random(r), TRIPS.random(r), ADDED_MS.random(r)) },
    { r -> handshakeCost(RTT_MS.random(r), HANDSHAKE_TRIPS.random(r)) },
    { r -> lossTail(LOSS_PERCENT.random(r), SEGMENTS.random(r)) },
    { r -> lossMean(LOSS_PERCENT.random(r)) },
    { r -> synBackoff(SYN_LOSSES.random(r)) },
    { r -> timeWaitCount(CLOSES.random(r)) },
    { r -> timeWaitPorts(CLOSES.random(r)) },
    { r -> lossShare(RETRANS.random(r), OUT_SEGS.random(r)) }
  )

  fun bandwidthDelay(gbps: Double, rttMs: Int): NumericQuestion {
    val bytesPerSecond = gbps * BITS_PER_GIGABIT / BITS_PER_BYTE
    val answer = bytesPerSecond * rttMs / MILLIS_PER_SECOND / BYTES_PER_MB
    return NumericQuestion(
      "A link carries ${shown(gbps)} Gbit/s. The RTT is $rttMs ms. How many MB must be in flight to keep the link full? (1 MB = 1,000,000 bytes)",
      answer, Tolerance.Relative(0.02), "MB",
      "${shown(gbps)} Gbit/s is ${shown(bytesPerSecond / BYTES_PER_MB)} MB/s. BDP = ${shown(bytesPerSecond / BYTES_PER_MB)} MB/s × ${shown(rttMs / MILLIS_PER_SECOND)} s = ${shown(answer)} MB. With less in flight, the link sits idle part of every round trip.",
      "Network math > Bandwidth-delay product"
    )
  }

  fun windowNeeded(mbps: Int, rttMs: Int): NumericQuestion {
    val bytes = mbps * BITS_PER_MEGABIT / BITS_PER_BYTE * rttMs / MILLIS_PER_SECOND
    val answer = bytes / BYTES_PER_KIB
    return NumericQuestion(
      "One TCP connection must carry $mbps Mbit/s over an RTT of $rttMs ms. What window does it need, in KiB? (1 KiB = 1024 bytes)",
      answer, Tolerance.Relative(0.02), "KiB",
      "The window must hold one BDP: $mbps Mbit/s / 8 × ${shown(rttMs / MILLIS_PER_SECOND)} s = ${shown(bytes)} bytes = ${shown(answer)} KiB. Above 64 KiB this needs window scaling.",
      "Network math > Bandwidth-delay product (window to fill a link)"
    )
  }

  fun windowThroughput(windowKib: Int, rttMs: Int): NumericQuestion {
    val bits = windowKib * BYTES_PER_KIB * BITS_PER_BYTE
    val answer = bits / (rttMs / MILLIS_PER_SECOND) / BITS_PER_MEGABIT
    return NumericQuestion(
      "A TCP connection has a $windowKib KiB window and an RTT of $rttMs ms. The link is 10 Gbit/s. What is the most this connection can carry, in Mbit/s?",
      answer, Tolerance.Relative(0.02), "Mbit/s",
      "Throughput ≤ window / RTT = $windowKib × 1024 × 8 bits / ${shown(rttMs / MILLIS_PER_SECOND)} s = ${shown(answer)} Mbit/s. The window is the limit, so a bigger link changes nothing.",
      "Network math > Throughput and the window"
    )
  }

  fun roundTripsPool(rps: Int, holdMs: Int, trips: Int, addedMs: Int): NumericQuestion {
    val newHoldMs = holdMs + trips * addedMs
    val answer = rps * newHoldMs / MILLIS_PER_SECOND
    return NumericQuestion(
      "Checkouts arrive at $rps/s and hold a DB connection for $holdMs ms each. Postgres moves to a zone that adds $addedMs ms per round trip. Each checkout makes $trips round trips while it holds the connection. How many connections are in use on average now?",
      answer, Tolerance.Relative(0.02), "connections",
      "The hold grows by $trips × $addedMs = ${trips * addedMs} ms, to $newHoldMs ms. Little's Law: $rps × ${shown(newHoldMs / MILLIS_PER_SECOND)} s = ${shown(answer)} connections, against ${shown(rps * holdMs / MILLIS_PER_SECOND)} before. Postgres sees none of it.",
      "Network math > Round trips add up"
    )
  }

  fun handshakeCost(rttMs: Int, handshakeTrips: Int): NumericQuestion {
    val answer = (handshakeTrips * rttMs).toDouble()
    val setup = if (handshakeTrips == 1) "a new TCP connection" else "a new TCP connection with TLS 1.3"
    return NumericQuestion(
      "The RTT to a service is $rttMs ms. A client stops reusing connections and opens $setup for every call. How many ms does each call add?",
      answer, Tolerance.Relative(0.01), "ms",
      "TCP costs 1 round trip before the request${if (handshakeTrips == 1) "" else ", and TLS 1.3 adds 1 more"}: $handshakeTrips × $rttMs = ${shown(answer)} ms per call. A reused connection pays only the call's own round trip.",
      "Network math > Round trips add up (new connections)"
    )
  }

  fun lossTail(lossPercent: Double, segments: Int): NumericQuestion {
    val p = lossPercent / PERCENT
    val answer = (1 - (1 - p).pow(segments)) * PERCENT
    return NumericQuestion(
      "${shown(lossPercent)}% of packets are lost at random. A call sends $segments segments, and any lost one makes the call wait for a retransmit. What percentage of calls wait?",
      answer, Tolerance.Relative(0.02), "%",
      "A call is safe only if all $segments segments arrive: (1 − ${shown(p)})^$segments. So 1 − ${shown(1 - p)}^$segments = ${shown(answer)}% of calls wait. Above 1%, that moves p99.",
      "TCP behaviour you feel in p99 > Retransmits and the minimum RTO"
    )
  }

  fun lossMean(lossPercent: Double): NumericQuestion {
    val answer = lossPercent / PERCENT * RTO_MIN_MS
    return NumericQuestion(
      "Each call is one small segment. ${shown(lossPercent)}% of them are lost, and each loss waits the Linux minimum of $RTO_MIN_MS ms. By how many ms does the mean call time rise?",
      answer, Tolerance.Relative(0.02), "ms",
      "${shown(lossPercent / PERCENT)} × $RTO_MIN_MS ms = ${shown(answer)} ms. The mean barely moves, but ${shown(lossPercent)}% of calls are $RTO_MIN_MS ms slower, so p99 jumps.",
      "TCP behaviour you feel in p99 > Retransmits and the minimum RTO (the mean hides it)"
    )
  }

  fun synBackoff(losses: Int): NumericQuestion {
    val waits = (0 until losses).map { SYN_RTO_S * 2.0.pow(it) }
    val answer = waits.sum()
    return NumericQuestion(
      "A client's SYN is lost $losses time(s) in a row, then gets through. The first RTO is $SYN_RTO_S s and it doubles after each timeout. How many seconds does connect() wait for the losses?",
      answer, Tolerance.Relative(0.01), "s",
      "The waits are ${waits.joinToString(" + ") { shown(it) }} = ${shown(answer)} s. A connect timeout under that turns a lost SYN into an error.",
      "TCP behaviour you feel in p99 > Retransmits and the minimum RTO (a lost SYN)"
    )
  }

  fun timeWaitCount(closesPerSecond: Int): NumericQuestion {
    val answer = (closesPerSecond * TIME_WAIT_S).toDouble()
    return NumericQuestion(
      "A Linux client opens and closes $closesPerSecond connections per second, and it closes first. About how many sockets sit in TIME_WAIT?",
      answer, Tolerance.Relative(0.02), "sockets",
      "Little's Law: $closesPerSecond closes/s × $TIME_WAIT_S s in TIME_WAIT = ${shown(answer)} sockets.",
      "TCP behaviour you feel in p99 > Connections and TIME_WAIT"
    )
  }

  fun timeWaitPorts(closesPerSecond: Int): NumericQuestion {
    val taken = closesPerSecond * TIME_WAIT_S
    val answer = taken.toDouble() / EPHEMERAL_PORTS * PERCENT
    return NumericQuestion(
      "A Linux client opens and closes $closesPerSecond connections per second to one destination IP and port, and it closes first. The local port range is the default 32768 to 60999. What percentage of the ports is held by TIME_WAIT?",
      answer, Tolerance.Relative(0.02), "%",
      "$closesPerSecond × $TIME_WAIT_S s = $taken ports in TIME_WAIT, out of $EPHEMERAL_PORTS. $taken / $EPHEMERAL_PORTS = ${shown(answer)}%. At 100%, connect() fails with EADDRNOTAVAIL.",
      "TCP behaviour you feel in p99 > Connections and TIME_WAIT (local ports)"
    )
  }

  fun lossShare(retransPerSecond: Double, outSegsPerSecond: Int): NumericQuestion {
    val answer = retransPerSecond / outSegsPerSecond * PERCENT
    return NumericQuestion(
      "api sends $outSegsPerSecond TCP segments per second and retransmits ${shown(retransPerSecond)} per second. What share of its segments are retransmits, in %?",
      answer, Tolerance.Relative(0.02), "%",
      "${shown(retransPerSecond)} / $outSegsPerSecond = ${shown(answer)}%. The counters are per network namespace and count all paths of api together. A loss on one path can be much higher than this average.",
      "Network panels in this lab > The network row (loss share)"
    )
  }

  private val GBPS = listOf(1.0, 2.5, 10.0)
  private val MBPS = listOf(100, 200, 500)
  private val RTT_MS = listOf(2, 5, 10, 20, 50)
  private val WINDOW_KIB = listOf(64, 256, 1024)
  private val RPS = listOf(15, 30, 60)
  private val HOLD_MS = listOf(20, 50)
  private val TRIPS = listOf(10, 20, 40)
  private val ADDED_MS = listOf(2, 3, 5)
  private val HANDSHAKE_TRIPS = listOf(1, 2)
  private val LOSS_PERCENT = listOf(0.5, 1.0, 2.0, 3.0)
  private val SEGMENTS = listOf(1, 3, 10)
  private val SYN_LOSSES = listOf(1, 2, 3)
  private val CLOSES = listOf(15, 50, 200, 400)
  private val RETRANS = listOf(2.0, 6.0, 12.0)
  private val OUT_SEGS = listOf(800, 2000, 4000)

  private const val BITS_PER_BYTE = 8
  private const val BITS_PER_GIGABIT = 1e9
  private const val BITS_PER_MEGABIT = 1e6
  private const val BYTES_PER_MB = 1e6
  private const val BYTES_PER_KIB = 1024
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val PERCENT = 100.0
  private const val RTO_MIN_MS = 200
  private const val SYN_RTO_S = 1
  private const val TIME_WAIT_S = 60
  private const val EPHEMERAL_PORTS = 60_999 - 32_768 + 1
}

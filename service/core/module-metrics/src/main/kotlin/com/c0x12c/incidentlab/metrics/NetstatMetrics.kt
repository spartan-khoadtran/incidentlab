package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** The TCP counters of this process's network namespace. Totals since the namespace was created; the two gauges are current counts. */
data class NetstatStats(
  val outSegs: Double,
  val retransSegs: Double,
  val activeOpens: Double,
  val passiveOpens: Double,
  val attemptFails: Double,
  val estabResets: Double,
  val currentEstablished: Double,
  val timeouts: Double,
  val lostRetransmit: Double,
  val synRetrans: Double,
  val listenOverflows: Double,
  val lossProbes: Double,
  val timeWait: Double
) {
  companion object {
    val EMPTY = NetstatStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
  }
}

/**
 * Reads `/proc/self/net/{snmp,netstat,sockstat}`. These files belong to the network namespace of
 * the process, so a container sees only its own sockets. A missing file or field reads as zero.
 */
class NetstatReader(private val root: Path = Path.of("/proc/self/net")) {

  fun read(): NetstatStats {
    val tcp = table(text(root.resolve("snmp")), "Tcp:")
    val ext = table(text(root.resolve("netstat")), "TcpExt:")
    val sockets = pairs(text(root.resolve("sockstat")), "TCP:")
    return NetstatStats(
      outSegs = tcp["OutSegs"] ?: 0.0,
      retransSegs = tcp["RetransSegs"] ?: 0.0,
      activeOpens = tcp["ActiveOpens"] ?: 0.0,
      passiveOpens = tcp["PassiveOpens"] ?: 0.0,
      attemptFails = tcp["AttemptFails"] ?: 0.0,
      estabResets = tcp["EstabResets"] ?: 0.0,
      currentEstablished = tcp["CurrEstab"] ?: 0.0,
      timeouts = ext["TCPTimeouts"] ?: 0.0,
      lostRetransmit = ext["TCPLostRetransmit"] ?: 0.0,
      synRetrans = ext["TCPSynRetrans"] ?: 0.0,
      listenOverflows = ext["ListenOverflows"] ?: 0.0,
      lossProbes = ext["TCPLossProbes"] ?: 0.0,
      timeWait = sockets["tw"] ?: 0.0
    )
  }

  private fun text(path: Path): String? =
    try {
      Files.readString(path)
    } catch (e: Exception) {
      null
    }

  /** The prefix starts two lines: a header of names, then a line of values in the same order. */
  private fun table(text: String?, prefix: String): Map<String, Double> {
    val lines = text.orEmpty().lines().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).trim().split(WHITESPACE) }
    if (lines.size < 2) {
      return emptyMap()
    }
    return lines[0].zip(lines[1]).mapNotNull { (name, value) -> value.toDoubleOrNull()?.let { name to it } }.toMap()
  }

  /** One line of `name value` pairs, such as `TCP: inuse 5 orphan 0 tw 12 alloc 20 mem 3`. */
  private fun pairs(text: String?, prefix: String): Map<String, Double> {
    val parts = text.orEmpty().lines().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.split(WHITESPACE) ?: return emptyMap()
    return parts.chunked(2).mapNotNull { pair -> pair.getOrNull(1)?.toDoubleOrNull()?.let { pair[0] to it } }.toMap()
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")
  }
}

/**
 * The network USE layer as the kernel counts it for this container: segments sent and
 * retransmitted, RTO timeouts, connections opened, TIME_WAIT sockets. One read serves every meter
 * of a scrape; the snapshot is reused for [ttl] so a scrape never touches the files more than once.
 */
class NetstatMetrics(
  private val reader: NetstatReader = NetstatReader(),
  private val clock: Clock = Clock.systemUTC(),
  private val ttl: Duration = Duration.ofMillis(500)
) : MeterBinder {

  private val cached = AtomicReference(Cached(NetstatStats.EMPTY, 0L))

  override fun bindTo(registry: MeterRegistry) {
    FunctionCounter.builder("net_tcp_out_segs", this) { it.latest().outSegs }
      .description("TCP segments sent").register(registry)
    FunctionCounter.builder("net_tcp_retrans_segs", this) { it.latest().retransSegs }
      .description("TCP segments retransmitted").register(registry)
    FunctionCounter.builder("net_tcp_active_opens", this) { it.latest().activeOpens }
      .description("TCP connections opened by this side (SYN sent)").register(registry)
    FunctionCounter.builder("net_tcp_passive_opens", this) { it.latest().passiveOpens }
      .description("TCP connections accepted (SYN received)").register(registry)
    FunctionCounter.builder("net_tcp_attempt_fails", this) { it.latest().attemptFails }
      .description("TCP connection attempts that failed").register(registry)
    FunctionCounter.builder("net_tcp_estab_resets", this) { it.latest().estabResets }
      .description("Established TCP connections ended by a RST").register(registry)
    Gauge.builder("net_tcp_current_established", this) { it.latest().currentEstablished }
      .description("TCP connections in the ESTABLISHED state").register(registry)
    FunctionCounter.builder("net_tcp_timeouts", this) { it.latest().timeouts }
      .description("TCP retransmission timeouts (RTO) that fired").register(registry)
    FunctionCounter.builder("net_tcp_lost_retransmit", this) { it.latest().lostRetransmit }
      .description("Retransmitted TCP segments that were lost again").register(registry)
    FunctionCounter.builder("net_tcp_syn_retrans", this) { it.latest().synRetrans }
      .description("SYN segments retransmitted").register(registry)
    FunctionCounter.builder("net_tcp_listen_overflows", this) { it.latest().listenOverflows }
      .description("SYNs dropped because the accept queue was full").register(registry)
    FunctionCounter.builder("net_tcp_loss_probes", this) { it.latest().lossProbes }
      .description("Tail loss probes sent").register(registry)
    Gauge.builder("net_tcp_time_wait", this) { it.latest().timeWait }
      .description("TCP sockets in the TIME_WAIT state").register(registry)
  }

  internal fun latest(): NetstatStats {
    val now = clock.millis()
    val current = cached.get()
    if (now - current.at < ttl.toMillis()) {
      return current.stats
    }
    val fresh = try {
      reader.read()
    } catch (e: Exception) {
      NetstatStats.EMPTY
    }
    cached.set(Cached(fresh, now))
    return fresh
  }

  private class Cached(val stats: NetstatStats, val at: Long)
}

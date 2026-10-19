package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NetstatMetricsTest {

  @Test
  fun `renders counters with _total and gauges without, and reads the files once per ttl`(@TempDir root: Path) {
    write(root, retrans = 27, timeWait = 812)
    var now = Instant.parse("2026-01-01T00:00:00Z")
    val clock = object : Clock() {
      override fun instant(): Instant = now

      override fun getZone() = ZoneOffset.UTC

      override fun withZone(zone: java.time.ZoneId): Clock = this
    }
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    NetstatMetrics(NetstatReader(root), clock, Duration.ofMillis(500)).bindTo(registry)

    val first = registry.scrape()
    assertThat(first).contains("net_tcp_retrans_segs_total 27.0")
    assertThat(first).contains("net_tcp_time_wait 812.0")
    assertThat(first).contains("net_tcp_timeouts_total 19.0")
    assertThat(first).contains("net_tcp_active_opens_total 310.0")
    assertThat(first).contains("net_tcp_current_established 17.0")

    write(root, retrans = 40, timeWait = 900)
    now = now.plusMillis(100)
    assertThat(registry.scrape()).contains("net_tcp_retrans_segs_total 27.0")

    now = now.plusMillis(500)
    val third = registry.scrape()
    assertThat(third).contains("net_tcp_retrans_segs_total 40.0")
    assertThat(third).contains("net_tcp_time_wait 900.0")
  }

  @Test
  fun `an unreadable namespace reads as zero instead of failing the scrape`(@TempDir root: Path) {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    NetstatMetrics(NetstatReader(root.resolve("missing"))).bindTo(registry)

    assertThat(registry.scrape()).contains("net_tcp_retrans_segs_total 0.0")
    assertThat(registry.get("net_tcp_time_wait").gauge().value()).isEqualTo(0.0)
  }

  private fun write(root: Path, retrans: Int, timeWait: Int) {
    Files.writeString(
      root.resolve("snmp"),
      "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts InCsumErrors\n" +
        "Tcp: 1 200 120000 -1 310 42 3 5 17 9000 8800 $retrans 0 4 0\n"
    )
    Files.writeString(root.resolve("netstat"), "TcpExt: ListenOverflows TCPTimeouts TCPLossProbes TCPLostRetransmit TCPSynRetrans\nTcpExt: 2 19 11 1 6\n")
    Files.writeString(root.resolve("sockstat"), "sockets: used 123\nTCP: inuse 5 orphan 0 tw $timeWait alloc 20 mem 3\n")
  }
}

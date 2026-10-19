package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NetstatReaderTest {

  @Test
  fun `reads the Tcp, TcpExt and sockstat lines`(@TempDir root: Path) {
    Files.writeString(
      root.resolve("snmp"),
      "Ip: Forwarding DefaultTTL InReceives\nIp: 1 64 500\n" +
        "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts InCsumErrors\n" +
        "Tcp: 1 200 120000 -1 310 42 3 5 17 9000 8800 27 0 4 0\n" +
        "Udp: InDatagrams NoPorts\nUdp: 10 0\n"
    )
    Files.writeString(
      root.resolve("netstat"),
      "TcpExt: SyncookiesSent ListenOverflows TCPTimeouts TCPLossProbes TCPLostRetransmit TCPSynRetrans\n" +
        "TcpExt: 0 2 19 11 1 6\n" +
        "IpExt: InNoRoutes InTruncatedPkts\nIpExt: 0 0\n"
    )
    Files.writeString(root.resolve("sockstat"), "sockets: used 123\nTCP: inuse 5 orphan 0 tw 812 alloc 20 mem 3\nUDP: inuse 1\n")

    val stats = NetstatReader(root).read()

    assertThat(stats.activeOpens).isEqualTo(310.0)
    assertThat(stats.passiveOpens).isEqualTo(42.0)
    assertThat(stats.attemptFails).isEqualTo(3.0)
    assertThat(stats.estabResets).isEqualTo(5.0)
    assertThat(stats.currentEstablished).isEqualTo(17.0)
    assertThat(stats.outSegs).isEqualTo(8800.0)
    assertThat(stats.retransSegs).isEqualTo(27.0)
    assertThat(stats.listenOverflows).isEqualTo(2.0)
    assertThat(stats.timeouts).isEqualTo(19.0)
    assertThat(stats.lossProbes).isEqualTo(11.0)
    assertThat(stats.lostRetransmit).isEqualTo(1.0)
    assertThat(stats.synRetrans).isEqualTo(6.0)
    assertThat(stats.timeWait).isEqualTo(812.0)
  }

  @Test
  fun `missing files and missing fields read as zero`(@TempDir root: Path) {
    Files.writeString(root.resolve("snmp"), "Tcp: RtoAlgorithm OutSegs\nTcp: 1 40\n")

    val stats = NetstatReader(root).read()

    assertThat(stats.outSegs).isEqualTo(40.0)
    assertThat(stats.retransSegs).isEqualTo(0.0)
    assertThat(stats.timeouts).isEqualTo(0.0)
    assertThat(stats.timeWait).isEqualTo(0.0)
  }

  @Test
  fun `a header without its value line reads as zero`(@TempDir root: Path) {
    Files.writeString(root.resolve("snmp"), "Tcp: RtoAlgorithm OutSegs\n")

    assertThat(NetstatReader(root).read()).isEqualTo(NetstatStats.EMPTY)
  }
}

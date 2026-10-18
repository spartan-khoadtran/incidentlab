package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/network.md: one situation, four readings, one of them right. */
internal object NetworkConcepts {

  val all: List<ChoiceQuestion> = listOf(
    ChoiceQuestion(
      text = "A backup copies over a 10 Gbit/s link to another region, 80 ms away. One TCP stream never passes about 6.5 Mbit/s. What limits it?",
      answer = "The window: one stream sends at most one window per round trip, and 64 KiB / 80 ms is about 6.5 Mbit/s",
      others = listOf(
        "The link: 10 Gbit/s links are shared, so the stream gets a fixed slice",
        "The disk on the other side: writes are slower than the network",
        "Packet loss: every stream across regions loses about 99% of its packets"
      ),
      explanation = "Throughput ≤ window / RTT. To fill the link the window must hold one BDP, here 10 Gbit/s × 80 ms = 100 MB. Window scaling or more streams fix it, not a bigger link.",
      doc = "Network math > Throughput and the window"
    ),
    ChoiceQuestion(
      text = "Why does the same link need more bytes in flight when the RTT grows?",
      answer = "The link drains bytes at its bandwidth, so to stay busy for one whole round trip it needs bandwidth × RTT bytes on the wire",
      others = listOf(
        "A longer RTT corrupts more packets, so TCP sends extra copies",
        "Routers buffer more on long paths, and the buffer counts as bytes in flight",
        "It does not: bytes in flight depend only on the bandwidth"
      ),
      explanation = "That product is the bandwidth-delay product. With fewer bytes in flight, the sender waits for ACKs while the link sits idle.",
      doc = "Network math > Bandwidth-delay product"
    ),
    ChoiceQuestion(
      text = "Postgres moved to another zone, about 3 ms away. The DBA sees flat exec times, the network team sees a normal 3 ms, but checkout got 100 ms slower. What is the likely reading?",
      answer = "Each checkout makes many queries one after another, and each one pays the extra 3 ms",
      others = listOf(
        "The network team is wrong: the RTT must really be 100 ms",
        "Postgres got slower, but its exec time only counts CPU time",
        "Checkout runs its queries in parallel, so their delays multiply"
      ),
      explanation = "Sequential round trips add up: about 33 queries × 3 ms is 100 ms. Each side sees a normal number. Count the queries per request and batch them.",
      doc = "Network math > Round trips add up"
    ),
    ChoiceQuestion(
      text = "A service sees 2% packet loss to a downstream. Its calls are small. What does that do to latency?",
      answer = "About 2% of calls wait 200 ms or more for a retransmit, so p99 jumps while p50 and the mean barely move",
      others = listOf(
        "Every call gets about 2% slower",
        "The calls fail with an error: TCP gives up on a lost packet",
        "Nothing: TCP resends a lost packet within one RTT, always"
      ),
      explanation = "A lost last segment has no duplicate ACKs behind it, so the sender waits for the tail loss probe or the RTO. On Linux that is at least about 200 ms.",
      doc = "TCP behaviour you feel in p99 > Retransmits and the minimum RTO"
    ),
    ChoiceQuestion(
      text = "api's p99 calling pricing rose, but pricing's own p99 is flat. api's TCP retransmits went from 0 to a steady rate. Why does pricing see nothing?",
      answer = "pricing's timer starts when the request arrives, and a lost copy never arrives, so the wait happens before pricing's clock starts",
      others = listOf(
        "pricing's metrics are broken: a server always sees its client's latency",
        "pricing retransmits too, but it does not count retransmits",
        "The loss is inside api's JVM, not on the network"
      ),
      explanation = "The sender counts the retransmit and pays the wait. Compare the two sides of the hop, then read the sender's TCP counters.",
      doc = "TCP behaviour you feel in p99 > Retransmits and the minimum RTO"
    ),
    ChoiceQuestion(
      text = "A connect timeout is set to 800 ms. On a lossy network, some connections fail although the server is healthy. Why?",
      answer = "A lost SYN is resent only after the first RTO of 1 s, so the timeout fires first",
      others = listOf(
        "800 ms is shorter than the RTT of any network",
        "The server's accept queue rejects connections under loss",
        "TIME_WAIT blocks new connections for 800 ms"
      ),
      explanation = "Before any RTT is measured, the RTO starts at 1 s and doubles after each loss. A connect timeout below 1 s turns one lost SYN into an error.",
      doc = "TCP behaviour you feel in p99 > Retransmits and the minimum RTO (a lost SYN)"
    ),
    ChoiceQuestion(
      text = "ss -s on a client shows thousands of sockets in TIME_WAIT, all towards one service. What is the most likely cause?",
      answer = "The client closes a connection after every call instead of reusing it",
      others = listOf(
        "The service closes its connections too early",
        "The network drops FIN packets, so sockets never close",
        "The client leaks file descriptors"
      ),
      explanation = "The side that closes first keeps TIME_WAIT for 60 s, so the count is about closes/s × 60. Many of them on the client means a new connection per call: turn on keep-alive.",
      doc = "TCP behaviour you feel in p99 > Connections and TIME_WAIT"
    ),
    ChoiceQuestion(
      text = "Why can many TIME_WAIT sockets towards one destination break new connections?",
      answer = "Each one holds a local port for that destination for 60 s, and the default range has only 28,232 ports",
      others = listOf(
        "Each one holds a large send buffer, and memory runs out",
        "The kernel stops accepting connections above 1,000 TIME_WAIT sockets",
        "They keep sending keep-alive packets that fill the link"
      ),
      explanation = "With tcp_tw_reuse at its default (loopback only), one destination runs out near 28,232 / 60 ≈ 470 new connections per second. connect() then fails with EADDRNOTAVAIL.",
      doc = "TCP behaviour you feel in p99 > Connections and TIME_WAIT (local ports)"
    ),
    ChoiceQuestion(
      text = "The host's TCP counters show no retransmits, but one container's calls look like loss. Why can the host miss it?",
      answer = "TCP counters are kept per network namespace, and each container has its own namespace",
      others = listOf(
        "The host only counts retransmits above 1%",
        "Containers do not use TCP, they use a virtual protocol",
        "Retransmits are counted by the receiver, and the receiver is outside the host"
      ),
      explanation = "/proc/net/snmp shows the namespace of the reader. Read the counters from inside the container, or from a tool that enters its namespace.",
      doc = "TCP behaviour you feel in p99 > Counters per network namespace"
    ),
    ChoiceQuestion(
      text = "In the lab, which panel tells you that api stopped reusing its connections to pricing?",
      answer = "New TCP connections per second at api rise to match its calls to pricing per second",
      others = listOf(
        "TCP segments retransmitted per second at pricing",
        "Established connections at Postgres",
        "pricing's p99 by route on the server side"
      ),
      explanation = "With reuse, opens stay near 0 while calls flow. One open per call means every call pays a handshake and leaves a TIME_WAIT socket on api.",
      doc = "Network panels in this lab > The network row"
    )
  )
}

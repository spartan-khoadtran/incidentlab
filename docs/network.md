# The network between services

The network is the layer people blame first and measure last. This page gives the numbers to check that blame. It covers how much data fits in a round trip, how round trips add up, and what TCP does when a packet is lost. The drills of `make quiz` and the network exercises of `make new` point at its sections.

## Network math

Three formulas cover most network questions in an incident. Each one needs only the RTT and one more number.

### Bandwidth-delay product

The bandwidth-delay product (BDP) is how many bytes are on the wire at once when a link is full.

BDP = bandwidth × RTT

- Example: 1 Gbit/s is 125 MB/s. With an RTT of 10 ms, the BDP is 125 MB/s × 0.01 s = 1.25 MB.
- A sender must keep that many bytes in flight to fill the link. With fewer bytes in flight, the link sits idle part of each round trip.
- A longer RTT needs more bytes in flight for the same bandwidth. The same link across a region needs a bigger window than inside one zone.

### Throughput and the window

One TCP connection sends at most one window per round trip.

throughput ≤ window / RTT

- The window is the smaller of two limits. One is the receive window the other side announces. The other is the congestion window the sender keeps.
- Example: a 64 KiB window and an RTT of 50 ms give at most 65,536 B / 0.05 s, about 1.3 MB/s. That is about 10.5 Mbit/s, even on a 10 Gbit/s link.
- Without window scaling, the receive window cannot pass 64 KiB. Window scaling (a TCP option) lifts that limit.
- Read it backwards in an incident. A transfer stuck at window / RTT is limited by the window, not by the link. A bigger link changes nothing.
- After a loss, the congestion window shrinks. So loss also cuts throughput, not only latency.

### Round trips add up

Calls that run one after another each pay the full RTT. Calls that run in parallel pay it about once.

| Pattern | Network time per request |
|---|---|
| 1 call | 1 × RTT |
| N calls one after another | N × RTT |
| N calls in parallel | about 1 × RTT |
| New TCP connection, then 1 call | 2 × RTT |
| New TCP and TLS 1.3 connection, then 1 call | 3 × RTT |
| New TCP and TLS 1.2 connection, then 1 call | 4 × RTT |

- Example: a request sends 40 queries one after another. Postgres moves to another zone, and the RTT grows by 3 ms. The request is 40 × 3 = 120 ms slower, and Postgres reports nothing new.
- The delay hides from both sides. The database sees fast queries. The network team sees a normal 3 ms.
- If the request holds a pool connection during those round trips, the hold time grows too. By Little's Law, more connections are in use, and the pool can saturate.
- The fix is fewer round trips: one batch query instead of 40, reused connections instead of new ones. Moving the server back is rarely needed.

## TCP behaviour you feel in p99

TCP hides loss and connection setup from the app. The app only sees a call that took longer. These costs are fixed numbers in Linux, so they show up as steps in the tail.

### Retransmits and the minimum RTO

When a segment is lost, TCP sends it again. How fast depends on what comes after it.

| Situation | How TCP finds the loss | Extra wait |
|---|---|---|
| More segments follow the lost one | Duplicate ACKs, then fast retransmit | About 1 RTT |
| The lost segment is the last one in flight | A tail loss probe, or the RTO timer | 200 ms or more on Linux |
| A SYN is lost | The RTO timer, with the first RTO at 1 s | 1 s, then 2 s for a second loss |

- The RTO is the retransmission timeout. TCP computes it from the measured RTT and its variation. On Linux its floor is 200 ms by default. Since Linux 6.11 the sysctl `tcp_rto_min_us` sets it. The standard asks for a 1 s floor, but Linux uses 200 ms.
- After each timeout, the RTO doubles. On a fast network, a segment lost twice costs about 200 ms, then about 400 ms more.
- A small request fits in one segment. If it is lost, nothing follows it, so no duplicate ACK can come. The sender waits for a timer.
- A tail loss probe resends the last segment before the RTO fires. With one segment in flight, Linux sends it after two RTTs plus 200 ms. It never waits longer than the RTO. Either way, the wait is about 200 ms.
- So a small loss rate moves p99, not p50. 2% loss on small calls means about 2% of calls wait 200 ms or more. p50 stays flat, and the mean moves by only about 4 ms.
- The receiver's timer starts when the data arrives. A copy that was lost never arrived, so the server's own latency stays flat. Only the sender's view shows the wait.
- How to see it: `nstat -az TcpRetransSegs TcpExtTCPTimeouts TcpExtTCPLossProbes` for the counters, `ss -ti` for the RTT, RTO and retransmits of each socket.

### Connections and TIME_WAIT

A new TCP connection costs one round trip (SYN, then SYN-ACK) before the first request can go. A closed connection leaves a socket behind.

- The side that closes first keeps the socket in TIME_WAIT. On Linux that lasts 60 s. It stops a late packet from the old connection from landing in a new one.
- By Little's Law, sockets in TIME_WAIT ≈ connections closed per second × 60 s. A client that opens and closes 50 connections per second keeps about 3,000 sockets in TIME_WAIT.
- TIME_WAIT sockets are cheap in memory. The real limit is local ports. Linux picks a client port from 32768 to 60999 (`ip_local_port_range`), so 28,232 ports.
- A connection to one destination IP and port needs a free local port. A port in TIME_WAIT to that destination stays taken for 60 s. `tcp_tw_reuse` lifts this, but its default (2) does that only for loopback traffic.
- So one destination runs out at about 28,232 / 60 ≈ 470 new connections per second. Then `connect()` fails with `EADDRNOTAVAIL`, while CPU and bandwidth look idle.
- The fix is connection reuse: HTTP keep-alive and a client connection pool. One reused connection serves thousands of calls with one handshake and no TIME_WAIT.
- How to see it: `ss -s` for the totals by state, `ss -tan state time-wait` for the sockets, the rate of `TcpActiveOpens` against the call rate.

### Counters per network namespace

Linux keeps TCP counters per network namespace. Each container has its own namespace, with its own sockets and its own counters.

- `/proc/net/snmp`, `/proc/net/netstat` and `/proc/net/sockstat` show the namespace of the process that reads them. `nstat` and `ss` read the same data.
- A host exporter reads the namespace it runs in. It does not see the retransmits or the TIME_WAIT sockets inside a container.
- So per-service TCP counters must come from inside the service, or from a tool that enters each namespace. On Kubernetes, all containers of a pod share one namespace, so the counters are per pod.
- The counters are totals since the namespace was created. Read them as a rate over time, like any counter.
- A retransmit is counted by the sender. Loss on the path from A to B shows in A's counters. B's counters may stay at zero.

## Network panels in this lab

Every Kotlin service in the lab (api, pricing, worker, loadgen, noisy, pgobserver) exports the TCP counters of its own namespace. The netem exercises shape api's outgoing traffic, so the evidence sits in api's counters.

### The network row

Dashboard `02 · USE by layer`, row "Network (per service)", and one panel in row 4 of `01 · RED by layer`.

| Panel | Metric | Healthy lab | What a change means |
|---|---|---|---|
| TCP segments retransmitted/s | `rate(net_tcp_retrans_segs_total[1m])` | About 0 | Loss on a path this service sends on |
| RTO timeouts and tail loss probes/s | `net_tcp_timeouts_total`, `net_tcp_loss_probes_total` | About 0 | A sender waited for a timer, not for duplicate ACKs |
| TCP connections opened/s | `rate(net_tcp_active_opens_total[1m])` | Low: the clients reuse their connections | Connections are not reused, or they keep breaking |
| Sockets in TIME_WAIT | `net_tcp_time_wait` | Low | This side closes connections first. About closes/s × 60 |
| TCP connections established | `net_tcp_current_established` | Flat | A pool that grows or a client that leaks connections |
| Calls to pricing: new TCP connections/s vs calls/s (RED row 4) | `http_client_connections_total` against `http_client_requests_total` | Opens near 0 while calls flow | One open per call: every call pays a handshake |

- `net_tcp_syn_retrans_total`, `net_tcp_lost_retransmit_total`, `net_tcp_attempt_fails_total`, `net_tcp_estab_resets_total`, `net_tcp_listen_overflows_total`, `net_tcp_passive_opens_total` and `net_tcp_out_segs_total` are exported too. Use them in Explore.
- Retransmits mean little alone. Divide by `net_tcp_out_segs_total` for a loss share, and compare with the latency of the calls on that path.
- Always read the two sides of a hop. api's view of pricing against pricing's own p99 tells you the time is lost between them. The network row tells you how.

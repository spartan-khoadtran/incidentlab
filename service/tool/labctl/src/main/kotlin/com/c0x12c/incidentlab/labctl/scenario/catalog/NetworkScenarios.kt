package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.AVERAGE_BASKET
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_HOLD_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CONNECTIONS_IN_USE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.SECONDS_PER_MINUTE
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import com.c0x12c.incidentlab.labctl.scenario.Symptom
import kotlin.math.roundToInt

/**
 * Faults on the wire between two services. The controller shapes api's outgoing traffic per
 * destination with netem (`docker.api.netem`), so the other side never sees the fault itself.
 */
internal object NetworkScenarios {

  val all: List<Scenario> = listOf(
    packetLoss(),
    rttAmplified(),
    noKeepalive()
  )

  private fun netem(target: String, delayMs: Number, lossPct: Number): Map<String, Map<String, Any?>> =
    mapOf("api" to mapOf("netem" to mapOf("target" to target, "delay_ms" to delayMs, "loss_pct" to lossPct)))

  private fun packetLoss() = Scenario(
    id = "packet_loss",
    title = "Packet loss between api and pricing",
    level = 2,
    topics = listOf("network", "layers"),
    // From 1.5%: below about 1% of calls, the slow calls may sit just under p99 and the alert never fires.
    params = { random, _ -> mapOf("loss" to LOSS_PERCENTS.random(random)) },
    faults = { p -> FaultSpec(docker = netem("pricing", 0, p.double("loss"))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 jumped and p50 did not move. The pricing team shows that their own p99 on /price is flat " +
        "and says the problem is not on their side. Find where the time goes."
    },
    symptom = { Symptom.TAIL_ONLY },
    hints = listOf(
      "Which downstream call of /checkout has the same tail? Compare api's view of pricing with pricing's own p99.",
      "The time is lost between api and pricing, not inside either one. What does the kernel inside api count when a segment never arrives?",
      "Open dashboard 04, row 3 (Network): retransmits and RTO timeouts at api. How long does Linux wait before it resends a lost segment?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("network"), signal = setOf("tcp_retransmits"), cause = "packet_loss", partial = setOf("downstream_slow")),
    explain = { p ->
      val share = p.double("loss") / PERCENT
      val meanMs = share * RTO_MIN_MS
      val retransmits = CHECKOUTS_PER_SECOND * share
      "About ${p.fixed(p.double("loss"), 1)}% of the packets api sends to pricing are lost on the way. A /price request is small and usually fits in one " +
        "TCP segment, so about ${p.fixed(p.double("loss"), 1)}% of the calls lose their request. Nothing is sent after it, so no duplicate ACK can trigger " +
        "a fast retransmit: api waits for a timer. On Linux the RTO is at least $RTO_MIN_MS ms. A tail loss probe, when Linux sends one, " +
        "waits two RTTs plus $RTO_MIN_MS ms while only one segment is in flight. So each of those calls is about $RTO_MIN_MS ms slower, more if the retransmit is lost too. " +
        "That share is above 1%, so p99 lands in the slow group while p50 does not move. The mean rises by only about ${p.fixed(meanMs, 0)} ms. " +
        "Inside api the kernel counts about ${p.fixed(retransmits, 2)} retransmits per second, against about 0 before. " +
        "pricing starts its timer when the request arrives, and the lost copy never arrives, so pricing's p99 stays flat. The pricing team is right."
    },
    steps = listOf(
      "Problem statement: /checkout p99 jumped, p50 is flat, and pricing says its own p99 is flat.",
      "Find the downstream with the same tail: api's calls to pricing have it, the DB and Kafka spans do not.",
      "Compare api's view of pricing with pricing's own p99: a few calls are $RTO_MIN_MS ms or more slower at api and none at pricing. The time is lost between the two.",
      "USE for the network at api: retransmits and RTO timeouts or loss probes go from about 0 to a steady rate.",
      "Check the size: the loss share times $CHECKOUTS_PER_SECOND calls/s matches the retransmit rate, and $RTO_MIN_MS ms per lost request matches the tail.",
      "The cause: packet loss on the path from api to pricing. Neither service is slow."
    ),
    evidence = listOf(
      "rate(net_tcp_retrans_segs_total{job=\"api\"}[1m])",
      "rate(net_tcp_timeouts_total{job=\"api\"}[1m]) + rate(net_tcp_loss_probes_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(http_client_duration_seconds_bucket{job=\"api\",target=\"pricing\",route=\"/price\"}[1m])))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"pricing\",route=\"/price\"}[1m])))",
      "histogram_quantile(0.5, sum by (le) (rate(http_client_duration_seconds_bucket{job=\"api\",target=\"pricing\",route=\"/price\"}[1m])))"
    ),
    cli = listOf(
      "nsenter -t $(pid api) -n nstat -az TcpRetransSegs TcpExtTCPTimeouts TcpExtTCPLossProbes   # run twice 10 s apart: TcpRetransSegs and the timeouts or probes grow",
      "nsenter -t $(pid api) -n ss -tin '( dport = :8001 )'   # retrans:x/y above 0 and rto:200 or more on the sockets to pricing",
      "curl -s http://api:8000/metrics | grep ^net_tcp_retrans   # net_tcp_retrans_segs_total keeps rising",
      "nsenter -t $(pid api) -n tc -s qdisc show dev eth0   # the netem leaf with loss and its dropped count: the loss is on api's way out"
    ),
    doc = "TCP behaviour you feel in p99 > Retransmits and the minimum RTO"
  )

  private fun rttAmplified() = Scenario(
    id = "rtt_amplified",
    title = "A few milliseconds of network, many round trips",
    level = 3,
    topics = listOf("network", "layers", "db"),
    // The query count follows the delay, so the pool lands between about 2.1 and 2.9 of 4 connections whatever the draw.
    params = { random, _ ->
      val delay = random.nextInt(3, 9)
      val trips = random.nextInt(AMPLIFIED_MS_MIN, AMPLIFIED_MS_MAX + 1) / (delay + TRIP_OVERHEAD_MS)
      mapOf("delay" to delay, "k" to ((trips - ORDER_TRIPS) / AVERAGE_BASKET).roundToInt().coerceAtLeast(MIN_REPEATS))
    },
    faults = { p ->
      FaultSpec(services = mapOf("api" to mapOf("n_plus_one" to p.int("k"))), docker = netem("postgres", p.int("delay"), 0))
    },
    marker = { "Infra: Postgres moved to zone b" },
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 is up a lot and /products is up a little. The DBA says Postgres is fine: " +
        "mean exec time per query is flat, and the extra queries are cheap."
    },
    symptom = { Symptom.SLOW_EVERY_ROUTE },
    hints = listOf(
      "Compare the query time api sees with the exec time Postgres counts. Then compare the DB session time with both.",
      "How many queries does one checkout send now? How much longer does each one take at api than inside Postgres?",
      "Multiply the round trips per checkout by the extra time per round trip. Then use Little's Law on the pool."
    ),
    answer = Answer(nature = setOf("arch", "amplified"), location = setOf("network", "api_db", "api"), signal = setOf("pool"), cause = "rtt_amplified", partial = setOf("n_plus_one")),
    explain = { p ->
      val delay = p.int("delay")
      val k = p.int("k")
      val queries = AVERAGE_BASKET * k
      val trips = queries + ORDER_TRIPS
      val tripMs = delay + TRIP_OVERHEAD_MS
      val addedMs = trips * tripMs
      val fullBasketMs = (MAX_BASKET * k + ORDER_TRIPS) * tripMs
      val inUse = inUse(p)
      "Postgres now sits about $delay ms away from api, so every query costs about $delay ms more, outside Postgres. Exec time stays flat. " +
        "Measured on the lab, one round trip then costs api about $delay + ${p.fixed(TRIP_OVERHEAD_MS, 1)} = ${p.fixed(tripMs, 1)} ms: the delay plus " +
        "the query, the driver and the TCP stack. " +
        "One checkout loads prices one product at a time, $k times per product: about ${p.fixed(queries, 0)} queries for an average basket " +
        "of $AVERAGE_BASKET items, then $ORDER_TRIPS more round trips to write the order. They run one after another, so the delay adds up: " +
        "about ${p.fixed(trips, 0)} x ${p.fixed(tripMs, 1)} ms = ${p.fixed(addedMs, 0)} ms per checkout on average, and ${p.fixed(fullBasketMs, 0)} ms for a basket of $MAX_BASKET. " +
        "A connection is held during every one of those round trips. By Little's Law the pool goes from about ${p.fixed(BASE_CONNECTIONS_IN_USE, 2)} to about " +
        "${p.fixed(inUse, 1)} of $POOL_MAX connections in use (${p.fixed(inUse / POOL_MAX * PERCENT, 0)}%), so checkouts also wait for a connection " +
        "now and then and p99 grows more than the sum. The pool is busy but not full, so this is not a pool too small for the load. " +
        "/products pays one round trip, and only on a cache miss. The N+1 was cheap on a fast network. " +
        "One batch query (WHERE id = ANY(...)) gives back about ${p.fixed((queries - 1) * tripMs, 0)} ms per checkout. Moving Postgres back is not needed."
    },
    steps = listOf(
      "Problem statement: /checkout p99 is up a lot, /products a little, and the DBA says exec time is flat.",
      "Compare two layers at the DB: the mean query time seen from api rose by a few ms, exec time inside Postgres did not. The app's query time excludes the pool wait, so the gap is the network.",
      "Count round trips: product_by_id per /checkout is far above 1, so one checkout makes many round trips in a row.",
      "Multiply: round trips per checkout times the extra ms per round trip gives most of the added checkout time.",
      "Little's Law on the pool: the connection is held for all of it, so connections in use climb to 2 or 3 of $POOL_MAX and checkouts sometimes wait.",
      "/products pays the delay once, on a cache miss only, so it moves a little.",
      "The cause: a small network delay multiplied by sequential round trips. Fix the round trips with one batch query."
    ),
    evidence = listOf(
      "sum(rate(db_client_duration_seconds_sum{job=\"api\"}[1m])) / sum(rate(db_client_duration_seconds_count{job=\"api\"}[1m]))",
      "sum(rate(pg_query_exec_seconds_total[1m])) / sum(rate(pg_query_calls_total[1m]))",
      "sum(rate(db_client_queries_total{job=\"api\",query=\"product_by_id\"}[1m])) / sum(rate(http_server_requests_total{job=\"api\",route=\"/checkout\"}[1m]))",
      "db_pool_in_use{job=\"api\"}",
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))"
    ),
    cli = listOf(
      "nsenter -t $(pid api) -n ss -tin '( dport = :5432 )'   # rtt: a few ms on every connection to Postgres, where the compose network alone is well under 1 ms",
      "nsenter -t $(pid api) -n tc qdisc show dev eth0   # the netem leaf with a delay towards Postgres"
    ),
    doc = "Network math > Round trips add up",
    baseLoad = true
  )

  private fun noKeepalive() = Scenario(
    id = "no_keepalive",
    title = "Every call to pricing opens a new connection",
    level = 3,
    topics = listOf("network", "layers"),
    params = { random, _ -> mapOf("delay" to random.nextInt(5, 11)) },
    faults = { p ->
      FaultSpec(services = mapOf("api" to mapOf("pricing_keepalive" to false)), docker = netem("pricing", p.int("delay"), 0))
    },
    marker = { "Deploy api v2.44: new HTTP client settings" },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, pricing moved to zone b as planned. The network team measured about ${p.int("delay")} ms of round trip between the zones. " +
        "api's calls to pricing now cost more than that, and pricing's own p99 is flat. Someone ran ss -s in the api container " +
        "and saw about ${timeWait()} sockets in TIME_WAIT."
    },
    symptom = { Symptom.CHANGE_TO_JUDGE },
    hints = listOf(
      "How much slower are api's calls to pricing, against the round trip the zone move adds? Compare p50, not only p99.",
      "A call over a reused connection costs one round trip. What else costs a round trip before the request can go?",
      "Compare new TCP connections per second at api with its calls to pricing per second, and look at TIME_WAIT."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api", "network"), signal = setOf("tcp_connections"), cause = "no_keepalive", partial = setOf("downstream_slow")),
    explain = { p ->
      val delay = p.int("delay")
      val ports = EPHEMERAL_PORTS / TIME_WAIT_SECONDS.toDouble()
      "v2.44 turned off connection reuse towards pricing. Every /price call opens a new TCP connection, and the SYN and SYN-ACK cost one round " +
        "trip before the request can go. With pricing about $delay ms away, a call pays about 2 x $delay = ${2 * delay} ms of network instead of $delay: " +
        "the move costs $delay ms, the missing reuse costs another $delay. At about $CHECKOUTS_PER_SECOND calls/s, api opens about " +
        "$CHECKOUTS_PER_SECOND connections per second and closes each one right after the response. The side that closes first keeps the socket " +
        "in TIME_WAIT for $TIME_WAIT_SECONDS s, so about $CHECKOUTS_PER_SECOND x $TIME_WAIT_SECONDS = ${timeWait()} sockets wait on api's side. " +
        "That is no outage yet: the local ports 32768 to 60999 give $EPHEMERAL_PORTS ports, so one destination runs out near " +
        "$EPHEMERAL_PORTS / $TIME_WAIT_SECONDS = ${p.fixed(ports, 0)} new connections per second. Fix: turn connection reuse back on. The zone move is fine."
    },
    steps = listOf(
      "Problem statement: pricing moved to a zone a few ms away, calls cost more than that, pricing's p99 is flat, and api holds many TIME_WAIT sockets.",
      "Compare api's view of pricing with pricing's own p50 and p99: api's p50 rose by about two round trips, pricing did not move. The time is outside pricing.",
      "A reused connection costs one round trip per call. The second round trip is a TCP handshake.",
      "Dashboard 04, row 3 (Network): api opens about $CHECKOUTS_PER_SECOND TCP connections per second. On dashboard 01, row 4, connections opened per second equal calls per second: no reuse.",
      "TIME_WAIT at api is about $CHECKOUTS_PER_SECOND x $TIME_WAIT_SECONDS = ${CHECKOUTS_PER_SECOND * TIME_WAIT_SECONDS}, because api closes each connection first and keeps the socket for $TIME_WAIT_SECONDS s.",
      "The cause: the new client settings turned off connection reuse. Turn it back on. The zone move is fine."
    ),
    evidence = listOf(
      "rate(net_tcp_active_opens_total{job=\"api\"}[1m])",
      "sum(rate(http_client_connections_total{job=\"api\",target=\"pricing\"}[1m])) / sum(rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "net_tcp_time_wait{job=\"api\"}",
      "histogram_quantile(0.5, sum by (le) (rate(http_client_duration_seconds_bucket{job=\"api\",target=\"pricing\",route=\"/price\"}[1m])))",
      "histogram_quantile(0.5, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"pricing\",route=\"/price\"}[1m])))"
    ),
    cli = listOf(
      "nsenter -t $(pid api) -n ss -s   # timewait near calls/s x 60",
      "nsenter -t $(pid api) -n ss -tan state time-wait '( dport = :8001 )'   # the TIME_WAIT sockets all point at pricing on port 8001",
      "nsenter -t $(pid api) -n nstat -az TcpActiveOpens   # run twice 10 s apart: it grows by about the pricing calls in those 10 s",
      "curl -s http://api:8000/metrics | grep ^net_tcp_time_wait   # net_tcp_time_wait near calls/s x 60",
      "nsenter -t $(pid api) -n tc qdisc show dev eth0   # the netem leaf with a delay towards pricing: the zone hop"
    ),
    doc = "TCP behaviour you feel in p99 > Connections and TIME_WAIT"
  )

  /** Connections in use by Little's Law: the hold of the price session plus the order session, at the checkout rate. */
  internal fun inUse(p: Params): Double {
    val delay = p.int("delay")
    val holdMs = BASE_CHECKOUT_HOLD_MS + (AVERAGE_BASKET * p.int("k") + ORDER_TRIPS) * (delay + TRIP_OVERHEAD_MS)
    return CHECKOUTS_PER_SECOND * holdMs / MILLIS_PER_SECOND
  }

  private fun timeWait(): Int = CHECKOUTS_PER_SECOND * TIME_WAIT_SECONDS

  private val LOSS_PERCENTS = listOf(1.5, 2.0, 2.5, 3.0)
  private const val RTO_MIN_MS = 200

  // INSERT order, INSERT items, COMMIT.
  private const val ORDER_TRIPS = 3
  private const val MAX_BASKET = 4
  private const val MIN_REPEATS = 4
  private const val POOL_MAX = 4
  private const val AMPLIFIED_MS_MIN = 95
  private const val AMPLIFIED_MS_MAX = 135

  // Measured with a 3 ms netem delay: api's mean query time went from 0.94 ms to 4.8 ms, so delay + 1.8 ms per round trip.
  internal const val TRIP_OVERHEAD_MS = 1.8

  private const val TIME_WAIT_SECONDS = SECONDS_PER_MINUTE
  private const val EPHEMERAL_PORTS = 60_999 - 32_768 + 1
}

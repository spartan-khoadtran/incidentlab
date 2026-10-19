package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_HOLD_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_SHARE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_TENANTS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.DEFAULT_PRODUCT_DETAIL_SECONDS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.round2
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import com.c0x12c.incidentlab.labctl.scenario.Symptom
import kotlin.math.roundToInt

/** Faults at the client and the load source: who sends what, how much, and how often it retries. */
internal object LoadScenarios {

  val all: List<Scenario> = listOf(
    tenantSpike(),
    globalSurge(),
    mixShift(),
    clientOutage(),
    retryStorm(),
    clientTimeout(),
    timeoutRetry(),
    surgeQueue()
  )

  private fun tenantSpike() = Scenario(
    id = "tenant_spike",
    title = "One tenant's traffic surges",
    level = 1,
    topics = listOf("red", "load"),
    // Calibrated on the cost of one lone product query. Concurrent scans share their reads, so under the crawl each query costs about a third of that.
    params = { random, context ->
      val cost = context.productDetailSeconds ?: DEFAULT_PRODUCT_DETAIL_SECONDS
      val extra = (CRAWLER_CPU_BUDGET / cost * random.nextDouble(0.9, 1.1)).roundToInt().coerceIn(MIN_CRAWLER_RPS, MAX_CRAWLER_RPS)
      mapOf("extra" to extra, "tenant" to "partner-x")
    },
    faults = { p -> FaultSpec(services = mapOf("loadgen" to mapOf("extra_rps" to mapOf(p.string("tenant") to p.int("extra"))))) },
    decoy = 0.5,
    ticket = { p ->
      "Alert at ${p.startedAt}: API p99 crossed its threshold and /checkout is getting slow. The mobile team asks whether the backend is slow. " +
        "According to the deploy calendar, nothing changed in checkout-api today."
    },
    symptom = { Symptom.SLOW_ONE_ROUTE },
    hints = listOf(
      "Start with RED at the LB and client layers: is the rate the same as before the incident?",
      "If the rate went up, do workload characterization: split the rate by tenant and by endpoint (RED dashboard, Client row).",
      "One tenant grew several times over and requests products that are not in the cache. Follow that load down to the DB pool and Postgres."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), signal = setOf("client_rate_by_tenant"), cause = "tenant_spike", partial = setOf("cache_miss")),
    explain = { p ->
      "Tenant ${p.string("tenant")} sends about ${p.int("extra")} extra requests/s to /products, walking the whole 100,000-product catalog like a crawler. " +
        "Almost every one of its requests misses the cache and runs the heavy product_detail query, so the api's 4-connection pool " +
        "saturates; everyone's /checkout slows down because it waits on the same pool. Rate rises at every layer at once: the pattern " +
        "'rate and duration rise together, so it may be load'. Workload characterization (who causes the load) points at the tenant right away. " +
        "The lower cache hit ratio here is a consequence of a new workload, not a broken cache: compared with the cache-miss exercise, the API rate is flat. " +
        "Fix: rate limit per tenant, give the crawler its own endpoint, or scale if this traffic is legitimate."
    },
    steps = listOf(
      "Problem statement: /checkout is slow and API p99 is over its threshold. The deploy calendar says nothing changed.",
      "RED at the LB and the client: the rate is up, not flat. Rate and duration rising together may be load.",
      "Workload characterization: split the client rate by tenant. One tenant grew several times over; the others are flat.",
      "Follow that load down: its requests miss the cache and run product_detail, so the 4-connection pool fills.",
      "Everyone's /checkout waits on the same pool, so it slows too. The cause is a real surge from one tenant.",
      "Check against the cache-miss exercise: here the API rate is up, so the low hit ratio comes from a new workload, not a broken cache."
    ),
    evidence = listOf(
      "sum by (tenant) (rate(client_requests_total[1m]))",
      "sum(rate(envoy_http_downstream_rq_total{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "db_pool_waiting{job=\"api\"}",
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))"
    ),
    doc = "RED method in detail > Rate; Combining with other methods > Workload characterization"
  )

  private fun globalSurge() = Scenario(
    id = "global_surge",
    title = "Traffic grows across every tenant",
    level = 1,
    topics = listOf("red", "load", "little"),
    params = { random, _ -> mapOf("multiplier" to round2(random.nextDouble(MIN_SURGE, MAX_SURGE))) },
    faults = { p -> FaultSpec(services = mapOf("loadgen" to mapOf("surge_multiplier" to p.double("multiplier")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 crossed its threshold. Marketing mentions a push notification campaign, " +
        "but nobody knows whether it already went out."
    },
    symptom = { Symptom.SLOW_ONE_ROUTE },
    hints = listOf(
      "RED at the LB: compare the rate with the baseline from 10 minutes ago.",
      "Split the client rate by tenant and by endpoint. Did one tenant grow, or all of them, in proportion?",
      "Follow the load into the pool: db_pool_in_use against db_pool_max. Which resource saturates first?"
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), signal = setOf("client_rate_by_tenant"), cause = "global_load"),
    explain = { p ->
      val total = (BASE_RPS * p.double("multiplier")).roundToInt()
      val checkouts = (CHECKOUTS_PER_SECOND * p.double("multiplier")).roundToInt()
      val need = checkouts * BASE_CHECKOUT_HOLD_MS / MILLIS_PER_SECOND
      "Every tenant sends about ${p.fixed(p.double("multiplier"), 1)} times its normal rate: about $total rps at the LB instead of the usual $BASE_RPS, and the same at api. " +
        "No tenant stands out and attempts per request stay at 1, so this is real demand. Duration rises with the rate because the first resource " +
        "to saturate is the 4-connection pool: about $checkouts checkouts/s times $BASE_CHECKOUT_HOLD_MS ms is " +
        "${p.fixed(need, 1)} connections in use on average, and queueing starts well before the pool is full. " +
        "Nothing in the system changed. Fix: scale api and the pool, or shed load per tenant until you can."
    },
    steps = listOf(
      "Problem statement: /checkout p99 is over its threshold. A push campaign may or may not have gone out.",
      "RED at the LB: the total rate is up against the baseline from 10 minutes ago.",
      "Split the client rate by tenant: every tenant grew in proportion, so no single one stands out.",
      "Attempts per user request stay at 1, so nobody retries. This is real demand.",
      "Follow it into the pool: in use reaches the max of 4, and queueing starts before the pool is full.",
      "Little's Law ties the checkout rate and the hold time to the connections in use. The cause is load across every tenant."
    ),
    evidence = listOf(
      "sum by (tenant) (rate(client_requests_total[1m]))",
      "sum(rate(envoy_http_downstream_rq_total{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "db_pool_in_use{job=\"api\"} / db_pool_max{job=\"api\"}",
      "sum(rate(client_attempts_total[1m])) / sum(rate(client_requests_total[1m]))"
    ),
    doc = "RED method in detail > Rate; Linking RED and USE > Why latency rises before 100%"
  )

  private fun mixShift() = Scenario(
    id = "mix_shift",
    title = "Same traffic, heavier mix",
    level = 2,
    topics = listOf("red", "load", "layers"),
    params = { random, _ -> mapOf("share" to round2(random.nextDouble(MIN_CHECKOUT_SHARE, MAX_CHECKOUT_SHARE))) },
    faults = { p -> FaultSpec(services = mapOf("loadgen" to mapOf("checkout_share" to p.double("share")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, the pricing team reports that their request rate doubled and asks whether api is retrying. " +
        "The DBA sees twice the writes on orders. The rate at the API gateway is flat."
    },
    symptom = { Symptom.RATE_CHANGED },
    hints = listOf(
      "Attempts per user request at the client: is anyone retrying?",
      "The total rate is flat. Split it by route. What moved?",
      "Compare the /checkout and /products rates with 10 minutes ago. One went up and one went down."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), signal = setOf("rate_by_route"), cause = "workload_mix"),
    explain = { p ->
      val checkouts = (BASE_RPS * p.double("share")).roundToInt()
      "The share of /checkout among user requests went from ${(BASE_CHECKOUT_SHARE * PERCENT).roundToInt()}% to ${p.percent("share")}. " +
        "The total stays at about $BASE_RPS rps, so the LB and api totals look flat, but /checkout went from about $CHECKOUTS_PER_SECOND/s to about $checkouts/s. " +
        "Each checkout costs a pricing call, a Kafka event and two DB sessions, so pricing, Kafka and Postgres all see about " +
        "${p.fixed(checkouts / CHECKOUTS_PER_SECOND.toDouble(), 1)} times their load. Nobody retries: attempts per request stay at 1. " +
        "A total rate hides a workload change; split the rate by route before you read it. " +
        "Fix: capacity for the new mix, and find out why users check out more (a promotion?)."
    },
    steps = listOf(
      "Problem statement: pricing says its rate doubled, the DBA sees twice the writes, and the gateway rate is flat.",
      "Attempts per user request at the client: still 1, so nobody retries.",
      "The total rate is flat, so split it by route: /checkout went up and /products went down.",
      "Each checkout costs a pricing call, a Kafka event and two DB sessions, so pricing, Kafka and Postgres all see more load.",
      "The cause is a mix that moved to the heavier endpoint. A total rate hides it, so read the rate by route."
    ),
    evidence = listOf(
      "sum by (route) (rate(http_server_requests_total{job=\"api\"}[1m]))",
      "sum(rate(client_attempts_total[1m])) / sum(rate(client_requests_total[1m]))",
      "sum(rate(http_server_requests_total{job=\"pricing\"}[1m]))"
    ),
    doc = "RED method in detail > Rate (split by endpoint)"
  )

  private fun clientOutage() = Scenario(
    id = "client_outage",
    title = "One client stopped calling",
    level = 1,
    topics = listOf("red", "load"),
    params = { random, context ->
      val tenants = context.tenants ?: BASE_TENANTS
      val tenant = listOf("ios", "android").random(random)
      mapOf("tenant" to tenant, "rps" to (tenants[tenant] ?: 0), "total" to tenants.values.sum())
    },
    faults = { p -> FaultSpec(services = mapOf("loadgen" to mapOf("tenant_outage" to p.string("tenant")))) },
    marker = { p -> if (p.string("tenant") == "ios") "Release: iOS app 5.3 rolled out to 100% of users" else "Release: Android app 7.1 rolled out to 100% of users" },
    decoy = 0.5,
    ticket = { p ->
      val lost = p.int("rps").toDouble() / p.int("total")
      "At ${p.startedAt}, the business dashboard shows orders down about ${(lost * PERCENT).roundToInt()}%. " +
        "Every service dashboard is green and nobody was paged."
    },
    symptom = { Symptom.RATE_CHANGED },
    hints = listOf(
      "A rate drop is a symptom too. Compare the LB rate with 10 minutes ago.",
      "Split the client rate by tenant. Who disappeared?",
      "Errors are zero at every layer because the requests never arrive. Look for what changed on that client."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), signal = setOf("client_rate_by_tenant"), cause = "client_outage"),
    explain = { p ->
      val rps = p.int("rps")
      "The ${p.string("tenant")} app stopped sending requests after its release: its $rps rps are gone, so the LB and api rate fell by about " +
        "${(rps.toDouble() / p.int("total") * PERCENT).roundToInt()}%. Errors and duration look perfect because the system has nothing to do. " +
        "A lower rate is a finding in its own right, and only the layer in front of the LB can show why. Fix: roll back the app release."
    },
    steps = listOf(
      "Problem statement: orders are down, every service dashboard is green, and nobody was paged.",
      "A drop in rate is a symptom too. Compare the LB rate with 10 minutes ago: it fell.",
      "Split the client rate by tenant: one tenant disappeared right at its app release.",
      "Errors and duration look perfect, because the system has nothing to do.",
      "Only the layer in front of the LB can explain it. The cause is a client that stopped calling after a bad release."
    ),
    evidence = listOf(
      "sum by (tenant) (rate(client_requests_total[1m]))",
      "sum(rate(envoy_http_downstream_rq_total{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))"
    ),
    doc = "RED method in detail > Rate (a drop is a symptom)"
  )

  private fun retryStorm() = Scenario(
    id = "retry_storm",
    title = "Retry storm",
    level = 2,
    topics = listOf("red", "layers"),
    params = { random, _ -> mapOf("err" to round2(random.nextDouble(0.6, 0.75)), "retries" to 4) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("error_rate" to p.double("err")), "loadgen" to mapOf("retries" to p.int("retries")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, customer support reports many customers cannot place orders. The dashboard shows traffic into /checkout jumping, " +
        "and someone proposes scaling api out."
    },
    symptom = { Symptom.ERRORS },
    hints = listOf(
      "Compare the requests users really send (logical requests at the client) with the rate at the LB. Are they still equal?",
      "The 'Attempts vs user requests' panel in the Client row: how many attempts does one user request become?",
      "Where do the /checkout errors at api come from? Look at api's http_client_requests_total towards pricing and at pricing's own RED."
    ),
    answer = Answer(nature = setOf("amplified"), location = setOf("client", "pricing"), signal = setOf("attempts_vs_requests"), cause = "retry_storm", partial = setOf("downstream_errors")),
    explain = { p ->
      "pricing answers 503 for about ${p.percent("err")} of /price requests, so api answers 502 for /checkout. The client retries immediately, " +
        "up to ${p.int("retries")} times, with no backoff. The number of requests users really send is unchanged, but attempts (and the rate at the LB " +
        "and at api) multiply. This is 'rate up but not real load': scaling api fixes nothing. " +
        "Fix: retry with backoff and jitter, cap retries (a retry budget), and fix the error in pricing."
    },
    steps = listOf(
      "Problem statement: many customers cannot order, /checkout traffic is jumping, and someone wants to scale api.",
      "Compare the logical user requests at the client with the rate at the LB: they no longer match.",
      "The Attempts vs user requests panel: one user request now becomes several attempts.",
      "Find the source of the errors: api's calls to pricing fail with 503, so api answers 502 and the client retries with no backoff.",
      "The cause is a retry storm. The real user count is flat, so scaling api fixes nothing. Fix pricing and add backoff to the client."
    ),
    evidence = listOf(
      "sum(rate(client_attempts_total{endpoint=\"checkout\"}[1m])) / sum(rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "sum by (outcome) (rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "sum by (status) (rate(http_server_requests_total{job=\"pricing\"}[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs; Linking RED and USE > Causal chain"
  )

  private fun clientTimeout() = Scenario(
    id = "client_timeout",
    title = "Client timeout shorter than server latency",
    level = 3,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("timeout" to random.nextInt(150, 201), "extra" to random.nextInt(230, 321)) },
    faults = { p ->
      FaultSpec(
        services = mapOf(
          "loadgen" to mapOf("checkout_timeout_ms" to p.int("timeout")),
          "pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to 0.5)
        )
      )
    },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the app reports 'timeout' errors when placing orders. The api team says no request failed: everything returned 200." },
    symptom = { Symptom.ERRORS },
    hints = listOf(
      "Compare errors at the client with errors at the LB and at api. At the client, which kind of error is it?",
      "Compare the server-side /checkout p99 with the timeout the client uses.",
      "How long does the server take to answer 200, and how long until the client gives up?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("client", "pricing"), signal = setOf("client_errors_vs_server"), cause = "client_timeout", partial = setOf("downstream_slow")),
    explain = { p ->
      "The client set a ${p.int("timeout")} ms timeout on /checkout, while half of the /price requests are about ${p.int("extra")} ms slower, " +
        "so the server-side p99 exceeds that timeout. The client gives up and records a timeout; the server finishes anyway and records a 200. " +
        "Timeouts disagree between layers: the client sees errors, the server sees success and wastes the work. " +
        "The LB shows no errors either: from its side the client just went away, so the timeouts exist only in the client's numbers. " +
        "Fix: set the client timeout from the server's real p99 plus a margin, and make the server stop work the client has abandoned."
    },
    steps = listOf(
      "Problem statement: the app reports timeouts on orders, and api says every request returned 200.",
      "Compare errors at the client with the LB and api: only the client sees them, and at the client they are timeouts.",
      "Compare the server-side /checkout p99 with the client's timeout: the p99 is longer.",
      "The client gives up and records a timeout. The server finishes and records a 200. The LB only sees the client leave.",
      "The cause: the client timeout is shorter than the server latency. Set it from the server's real p99."
    ),
    evidence = listOf(
      "sum by (outcome) (rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))"
    ),
    doc = "RED at many layers > Why Errors differ (mismatched timeouts)"
  )

  private fun timeoutRetry() = Scenario(
    id = "timeout_retry",
    title = "Client timeouts turn into retries",
    level = 3,
    topics = listOf("red", "layers"),
    params = { random, _ ->
      mapOf("timeout" to random.nextInt(250, 301), "extra" to random.nextInt(400, 501), "frac" to round2(random.nextDouble(0.3, 0.4)), "retries" to 2)
    },
    faults = { p ->
      FaultSpec(
        services = mapOf(
          "loadgen" to mapOf("checkout_timeout_ms" to p.int("timeout"), "retries" to p.int("retries")),
          "pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to p.double("frac"))
        )
      )
    },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout traffic at api is up by a third or more, but there are no more shoppers than usual. " +
        "The app shows some timeout errors, and support reports a few orders placed twice."
    },
    symptom = { Symptom.RATE_CHANGED },
    hints = listOf(
      "Compare the user requests at the client with the attempts it sends. Are they still equal?",
      "Which outcome do the failed attempts have at the client: 5xx or timeout?",
      "Compare the client's /checkout timeout with the server-side /checkout p99 and with pricing's p99."
    ),
    answer = Answer(
      nature = setOf("amplified"),
      location = setOf("client", "pricing"),
      signal = setOf("attempts_vs_requests", "client_errors_vs_server"),
      cause = "retry_storm",
      partial = setOf("client_timeout", "downstream_slow")
    ),
    steps = listOf(
      "Problem statement: /checkout traffic is up by a third or more with no more shoppers, the app shows timeouts, and some orders are placed twice.",
      "Rate up but shoppers flat: is this real demand? Compare user requests with attempts at the client: attempts are higher.",
      "The failed attempts at the client are timeouts, not 5xx. The server records 200s for them.",
      "Compare the server-side /checkout p99 with the client's timeout: part of the checkouts take longer than the timeout.",
      "Find the slow part: api's calls to pricing and pricing's own p99 rose. Part of the /price calls are slow.",
      "The cause: slow calls make timeouts, timeouts make immediate retries, retries add load. A retry storm fed by timeouts."
    ),
    explain = { p ->
      val f = p.double("frac")
      val attempts = 1 + f + f * f
      "About ${p.percent("frac")} of /price calls are about ${p.int("extra")} ms slower, so those checkouts take longer than the client's " +
        "${p.int("timeout")} ms timeout. The client gives up and retries right away, up to ${p.int("retries")} times, and each retry has the same chance " +
        "to be slow. One user checkout becomes about ${p.fixed(attempts, 2)} attempts on average, so the rate at the LB and at api rises while the real demand " +
        "is flat. The server finishes the abandoned attempts and records 200s, so api sees more traffic, not errors. " +
        "Each abandoned attempt still writes its order, so a retry without an idempotency key also places duplicate orders. This is the loop in the causal chain: " +
        "slowness makes timeouts, timeouts make retries, retries make load. Fix: backoff and a retry budget, a timeout set from the server's real p99, " +
        "an idempotency key on /checkout, and the slow path in pricing."
    },
    evidence = listOf(
      "sum(rate(client_attempts_total{endpoint=\"checkout\"}[1m])) / sum(rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "sum by (outcome) (rate(client_attempts_total{endpoint=\"checkout\"}[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"pricing\",route=\"/price\"}[1m])))"
    ),
    doc = "Linking RED and USE > Causal chain; RED at many layers > Why Rate differs (retry)"
  )

  private fun surgeQueue() = Scenario(
    id = "surge_queue",
    title = "A real surge queues before the app",
    level = 2,
    topics = listOf("load", "layers", "little"),
    params = { random, _ -> mapOf("multiplier" to round2(random.nextDouble(2.5, 3.0)), "limit" to 4) },
    faults = { p ->
      FaultSpec(services = mapOf("loadgen" to mapOf("surge_multiplier" to p.double("multiplier")), "api" to mapOf("admission_limit" to p.int("limit"))))
    },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, users say the app is slow. A TV ad went out a few minutes ago. The api team says their APM p99 is normal. " +
        "Last week they set api's concurrency limit to ${p.int("limit")} to protect the DB, and it has been fine since."
    },
    symptom = { Symptom.SLOW_BUT_API_FINE },
    hints = listOf(
      "RED at the LB: is the rate the same as 10 minutes ago? Split it by tenant.",
      "Put the LB p99 and the api p99 on one chart. Where is the gap?",
      "Little's Law: requests in the handler at the new rate, against the concurrency limit."
    ),
    answer = Answer(
      nature = setOf("load"),
      location = setOf("client", "lb"),
      signal = setOf("client_rate_by_tenant", "lb_vs_api_duration"),
      cause = "global_load",
      partial = setOf("queue_before_app")
    ),
    steps = listOf(
      "Problem statement: users say slow, a TV ad just went out, APM p99 is normal.",
      "RED at the LB: the rate is up 2.5 to 3 times. Split by tenant: every tenant grew, so it is real demand.",
      "Compare the LB p99 with the api p99: the LB is much slower. The time is spent before the handler starts.",
      "USE for the admission gate: waiting is above 0, and the admission wait p99 matches the gap.",
      "Little's Law: about 1 request in the handler at normal load becomes 2.5 to 3 at the new rate, close to the limit of 4. Bursts queue.",
      "The cause: real load across every tenant. The limit is where it queues, which is why APM cannot see it."
    ),
    explain = { p ->
      val inflight = BASE_IN_HANDLER * p.double("multiplier")
      "Every tenant sends about ${p.fixed(p.double("multiplier"), 1)} times its normal rate, so this is real demand. api lets only ${p.int("limit")} requests " +
        "into the handler at once. At the normal rate that was enough: about ${p.fixed(BASE_IN_HANDLER, 1)} request is in the handler on average. " +
        "At the new rate Little's Law gives about ${p.fixed(inflight, 1)} in flight against a limit of ${p.int("limit")}, so bursts queue in front of the handler. " +
        "APM starts its timer when the handler starts, so api's p99 stays flat while the LB sees the queue. Compare with the queue exercise where " +
        "the rate is flat: there the limit changed; here the load changed and met an old limit. Fix: raise the limit with the pool and the DB in mind, " +
        "or scale out api."
    },
    evidence = listOf(
      "sum by (tenant) (rate(client_requests_total[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(envoy_http_downstream_rq_time_bucket{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))) / 1000",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le) (rate(app_admission_wait_seconds_bucket{job=\"api\"}[1m])))"
    ),
    doc = "RED at many layers > Why Duration differs; Combining with other methods > Workload characterization"
  )

  private const val CRAWLER_CPU_BUDGET = 5.0
  private const val MIN_CRAWLER_RPS = 40
  private const val MAX_CRAWLER_RPS = 600
  private const val MIN_SURGE = 3.8
  private const val MAX_SURGE = 4.4
  private const val MIN_CHECKOUT_SHARE = 0.65
  private const val MAX_CHECKOUT_SHARE = 0.75
  private const val BASE_IN_HANDLER = 1.0
}

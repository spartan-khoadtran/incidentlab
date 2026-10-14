package com.c0x12c.redlab.labctl.scenario

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Catalog of lab incidents. Spoiler warning: this file holds the answers. Try the exercises first.
 *
 * Each scenario draws random parameters, turns them into fault overrides per service, and carries
 * the ticket, the hints, the accepted answer and the explanation with its PromQL evidence.
 */
object ScenarioCatalog {

  val BASE_TENANTS: Map<String, Int> = mapOf("web" to 20, "ios" to 15, "android" to 12, "partner-x" to 3)
  val BASE_RPS: Int = BASE_TENANTS.values.sum()
  private const val BASE_CHECKOUT_SHARE = 0.3
  private val CHECKOUTS_PER_SECOND: Int = (BASE_RPS * BASE_CHECKOUT_SHARE).roundToInt()
  private val PRODUCTS_PER_SECOND: Int = BASE_RPS - CHECKOUTS_PER_SECOND
  private val BASE_CONNECTIONS_IN_USE: Double = CHECKOUTS_PER_SECOND * BASE_CHECKOUT_HOLD_MS / MILLIS_PER_SECOND

  val all: List<Scenario> = listOf(
    tenantSpike(),
    globalSurge(),
    mixShift(),
    clientOutage(),
    retryStorm(),
    poolHold(),
    poolShrunk(),
    nPlusOne(),
    cpuThrottle(),
    singleThreadCpu(),
    queueBeforeApp(),
    lbNoHealthy(),
    downstreamSlow(),
    downstreamErrors(),
    apiTimeout(),
    pricingCpu(),
    cacheMiss(),
    clientTimeout(),
    consumerBacklog(),
    consumerSlow(),
    poisonMessage(),
    dbLock(),
    oom(),
    noisyNeighbor()
  )

  val byId: Map<String, Scenario> = all.associateBy { it.id }

  // ---------------------------------------------------------------- RED / load
  private fun tenantSpike() = Scenario(
    id = "tenant_spike",
    title = "One tenant's traffic surges",
    level = 1,
    topics = listOf("red", "load"),
    // Crawler rate calibrated to this machine: aim for about 90% of 2 Postgres cores on cache misses.
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
    hints = listOf(
      "Start with RED at the LB and client layers: is the rate the same as before the incident?",
      "If the rate went up, do workload characterization: split the rate by tenant and by endpoint (RED dashboard, Client row).",
      "One tenant grew several times over and requests products that are not in the cache. Follow that load down to the DB pool and Postgres."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("client"), signal = setOf("client_rate_by_tenant"), cause = "tenant_spike", partial = setOf("cache_miss")),
    explain = { p ->
      "Tenant ${p.string("tenant")} sends about ${p.int("extra")} extra requests/s to /products, walking the whole 100,000-product catalog like a crawler. " +
        "Almost every one of its requests misses the cache and runs the heavy product_detail query, so the api's 4-connection pool and Postgres CPU " +
        "saturate; everyone's /checkout slows down because it waits on the same pool. Rate rises at every layer at once: the pattern " +
        "'rate and duration rise together, so it may be load'. Workload characterization (who causes the load) points at the tenant right away. " +
        "The lower cache hit ratio here is a consequence of a new workload, not a broken cache: compared with the cache-miss exercise, the API rate is flat. " +
        "Fix: rate limit per tenant, give the crawler its own endpoint, or scale if this traffic is legitimate."
    },
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
    evidence = listOf(
      "sum(rate(client_attempts_total{endpoint=\"checkout\"}[1m])) / sum(rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "sum by (outcome) (rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "sum by (status) (rate(http_server_requests_total{job=\"pricing\"}[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs; Linking RED and USE > Causal chain"
  )

  // ---------------------------------------------------------------- layers / pool
  private fun poolHold() = Scenario(
    id = "pool_hold",
    title = "Connection pool exhausted after a deploy",
    level = 2,
    topics = listOf("layers", "use", "little"),
    // Half the runs overload the pool (503s), half sit just under it (slow, almost no errors).
    params = { random, _ ->
      val overload = random.nextBoolean()
      mapOf("recommend_ms" to if (overload) random.nextInt(290, 351) else random.nextInt(160, 191), "overload" to overload)
    },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("recommend_in_txn" to true), "pricing" to mapOf("recommend_ms" to p.int("recommend_ms")))) },
    marker = { "Deploy checkout-api v2.31: product suggestions added to the checkout step" },
    decoy = 0.5,
    ticket = { p ->
      val symptom = if (p.bool("overload")) "/checkout p99 jumps and 503s start" else "/checkout p99 jumps several times over; errors are rare"
      "At ${p.startedAt}, alert: $symptom. The checkout team just deployed v2.31. " +
        "The DB team says 'Postgres is fine'. Who is right, and where is the problem?"
    },
    hints = listOf(
      "Compare 'DB as seen from api' (db_client_session_seconds) with 'DB as seen from Postgres' (exec time from pgobserver).",
      "Where is the gap? Look at the Software resources row of the USE dashboard: pool in use, waiting, acquire wait.",
      "Little's Law: the /checkout rate times the connection hold time, against pool_max = 4. In a /checkout trace, which call sits inside db.session?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api_db", "api"), signal = setOf("pool"), cause = "pool_exhausted"),
    explain = { p ->
      val hold = BASE_CHECKOUT_HOLD_MS + PRICE_BASE_MS + p.int("recommend_ms")
      val holdSeconds = hold / MILLIS_PER_SECOND
      val need = CHECKOUTS_PER_SECOND * holdSeconds
      val outcome = if (p.bool("overload")) {
        "above the pool of 4. The pool saturates: requests wait for a connection and get a 503 after 2 s."
      } else {
        "just under the pool of 4. At that utilization the wait time explodes (queueing theory): almost nothing fails, everything waits."
      }
      "v2.31 calls pricing /price and /recommend (about ${p.int("recommend_ms")} ms) while still holding the connection with a transaction open. " +
        "The connection hold time per checkout goes from about $BASE_CHECKOUT_HOLD_MS ms to about $hold ms. At about $CHECKOUTS_PER_SECOND checkouts/s, " +
        "Little's Law gives L = $CHECKOUTS_PER_SECOND x ${p.fixed(holdSeconds, 3)} ≈ ${p.fixed(need, 1)} connections, $outcome " +
        "Postgres is not slow: exec time is flat. What Postgres does show is connections 'idle in transaction': each one holds a transaction open " +
        "while api waits for pricing. Fix: call /recommend outside the transaction, or asynchronously. Raising the pool only hides the problem " +
        "and may hit max_connections when you scale."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "db_pool_in_use{job=\"api\"}, db_pool_waiting{job=\"api\"}, db_pool_max{job=\"api\"}",
      "pg_connections{state=\"idle in transaction\"}"
    ),
    doc = "End-to-end investigation example; Linking RED and USE > Little's Law"
  )

  private fun poolShrunk() = Scenario(
    id = "pool_shrunk",
    title = "Connection pool shrunk by a config change",
    level = 2,
    topics = listOf("layers", "use", "little"),
    params = { _, _ -> mapOf("pool_max" to 1) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("pool_max" to p.int("pool_max")))) },
    marker = { "Config change: checkout-api database settings updated by the platform team" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 rose several times over. The DB team says Postgres is nearly idle. " +
        "The checkout team says their code did not change."
    },
    hints = listOf(
      "Compare DB session time from api with exec time inside Postgres. Where is the gap?",
      "Pool row on the USE dashboard: in use, waiting, and the max. Did the hold time change, or the limit?",
      "Little's Law: $CHECKOUTS_PER_SECOND checkouts/s times about $BASE_CHECKOUT_HOLD_MS ms is about ${String.format(Locale.ROOT, "%.1f", BASE_CONNECTIONS_IN_USE)} connections. " +
        "Against which pool_max is that now a problem?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api_db", "api"), signal = setOf("pool"), cause = "pool_exhausted"),
    explain = { p ->
      "pool_max was lowered from 4 to ${p.int("pool_max")}. The hold time per checkout did not change, so Little's Law still gives about " +
        "${p.fixed(BASE_CONNECTIONS_IN_USE, 1)} connections in use on average. That is ${(BASE_CONNECTIONS_IN_USE / p.int("pool_max") * PERCENT).roundToInt()}% utilization of the new pool, " +
        "and queueing theory says the wait grows fast from there: every burst of checkouts waits for the one connection. Postgres runs the same " +
        "queries at the same speed. Compared with the v2.31 exercise, the hold time (numerator) is flat and the limit (denominator) moved. " +
        "Utilization only means something against the limit of the right layer."
    },
    evidence = listOf(
      "db_pool_max{job=\"api\"}, db_pool_in_use{job=\"api\"}, db_pool_waiting{job=\"api\"}",
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "USE at many layers > Common examples (pool full while the DB idles); Combining with other methods > Methods from chapter 2 (Static performance tuning)"
  )

  private fun nPlusOne() = Scenario(
    id = "n_plus_one",
    title = "N+1 queries after an ORM change",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("k" to random.nextInt(6, 11)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("n_plus_one" to p.int("k")))) },
    marker = { "Deploy checkout-api v2.30: data layer moved to a new ORM" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, the DBA reports that the number of queries into Postgres multiplied and asks whether there is a marketing campaign. " +
        "Marketing says there is none."
    },
    hints = listOf(
      "Compare the rate at the API with the query rate at Postgres. Do they rise in the same proportion?",
      "Split the query rate by query name. Which query is new or jumped?",
      "Divide product_by_id queries per second by /checkout requests per second: how many queries does one checkout send?"
    ),
    answer = Answer(nature = setOf("amplified"), location = setOf("api_db", "api"), signal = setOf("query_rate_by_name"), cause = "n_plus_one"),
    explain = { p ->
      val queries = (AVERAGE_BASKET * p.int("k")).roundToInt()
      "The new ORM loads prices one product at a time, ${p.int("k")} times per product (lazy loading). An average checkout of $AVERAGE_BASKET products " +
        "turns into about $queries queries instead of 1. The API rate is flat but the DB rate is up: the fan-out grew, not the users. " +
        "Each query is fast inside Postgres and /checkout gets only a little slower, so users barely notice: the cost lands on the DB as load. " +
        "Fix: load in batches (WHERE id = ANY(...)) or eager loading."
    },
    evidence = listOf(
      "sum by (query) (rate(db_client_queries_total{job=\"api\"}[1m]))",
      "sum(rate(db_client_queries_total{job=\"api\",query=\"product_by_id\"}[1m])) / sum(rate(http_server_requests_total{job=\"api\",route=\"/checkout\"}[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs (fan-out)"
  )

  // ---------------------------------------------------------------- USE / container, runtime
  private fun cpuThrottle() = Scenario(
    id = "cpu_throttle",
    title = "CPU throttling at the container",
    level = 2,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("cpus" to round2(random.nextDouble(MIN_THROTTLE_CPUS, MAX_THROTTLE_CPUS))) },
    faults = { p -> FaultSpec(docker = mapOf("api" to mapOf("cpus" to p.double("cpus")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, p99 rose on every endpoint, including /products which is normally very fast. The rate is normal. " +
        "A teammate looked at the host's CPU and said 'plenty of CPU left, it is not CPU'."
    },
    hints = listOf(
      "Rate flat, duration up on every route: suspect architecture. Do the LB and api rise together?",
      "Do USE per layer for api: host, container, runtime. Which layer's limit should utilization be measured against?",
      "Look at CPU usage against cgroup_cpu_limit_cores, and at api's share of throttled periods."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container"), signal = setOf("cgroup_cpu"), cause = "cpu_throttle"),
    explain = { p ->
      val quotaMs = (p.double("cpus") * CFS_PERIOD_MS).roundToInt()
      "The api container's CPU limit was lowered to ${p.double("cpus")} cores: each 100 ms period gets only $quotaMs ms of CPU. " +
        "When a burst of requests uses up the quota, the whole process waits for the next period. Host CPU barely changes. At the cgroup layer, " +
        "CPU use against the limit jumps several times over and the share of throttled periods climbs from 0 to 10% or more. " +
        "A 1-minute average still looks below 100% because the quota runs out inside single 100 ms periods, which is what the throttle counter shows. " +
        "This is the 'lower layer hides the upper one' case from USE at many layers."
    },
    evidence = listOf(
      "rate(cgroup_cpu_usage_seconds_total{job=\"api\"}[1m]) / cgroup_cpu_limit_cores{job=\"api\"}",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])",
      "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / count(node_cpu_seconds_total{mode=\"idle\"})"
    ),
    doc = "USE at many layers > One layer hides another"
  )

  private fun singleThreadCpu() = Scenario(
    id = "single_thread_cpu",
    title = "One core saturated by CPU work on the event loop",
    level = 2,
    topics = listOf("use", "runtime"),
    params = { random, _ -> mapOf("burn" to random.nextInt(18, 23)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("cpu_burn_ms" to p.int("burn")))) },
    marker = { "Deploy checkout-api v2.32: the product page renders an extra detailed-reviews block" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, both /products and /checkout are slow although the rate is normal. " +
        "Someone proposes raising api's CPU limit from 2 to 4 cores."
    },
    hints = listOf(
      "How much CPU does the api container use against its 2-core limit? Is it throttled?",
      "api runs on one event loop thread: how many cores can it really use? Look at process CPU and event loop lag.",
      "In a /products trace, which span takes the time? Why does /checkout slow down too?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("one_core_event_loop"), cause = "single_thread_cpu"),
    explain = { p ->
      val cores = PRODUCTS_PER_SECOND * p.int("burn") / MILLIS_PER_SECOND
      "v2.32 adds about ${p.int("burn")} ms of CPU per /products, run on the event loop thread. At about $PRODUCTS_PER_SECOND requests/s " +
        "that is about ${p.fixed(cores, 2)} cores of CPU on a single thread. The event loop is close to saturated, so every other request " +
        "(including /checkout) waits: event loop lag rises. The container uses about 1 of its 2 cores and is not throttled, so raising the limit " +
        "to 4 cores does nothing. This is the 'single-threaded application' example in section 2.3.8 Load vs. Architecture."
    },
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"api\"}[1m])))",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core)"
  )

  private fun queueBeforeApp() = Scenario(
    id = "queue_before_app",
    title = "Requests queue before the app accepts them",
    level = 2,
    topics = listOf("layers", "red"),
    params = { _, _ -> mapOf("limit" to 1) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("admission_limit" to p.int("limit")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, users complain the app is slow. The api team looks at APM and says their p99 is still normal, so it must be the network." },
    hints = listOf(
      "Put the LB (Envoy) p99 and the api p99 on the same chart.",
      "The gap between the two lines is time the request spends where? See the Software resources row of the USE dashboard.",
      "Look at the Admission wait p99 panel (app_admission_wait_seconds)."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("lb", "api"), signal = setOf("lb_vs_api_duration"), cause = "queue_before_app"),
    explain = { p ->
      "The api's concurrency limit was set to ${p.int("limit")}: only one request at a time enters the handler, the rest wait ahead of it. " +
        "The duration api measures (APM) starts when the handler begins, so it stays flat. The LB measures from the moment it receives the request, " +
        "so it sees the wait too. The gap between LB latency and api latency is the queueing time: saturation showing up as duration."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(envoy_http_downstream_rq_time_bucket{envoy_http_conn_manager_prefix=\"ingress\"}[1m]))) / 1000",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le) (rate(app_admission_wait_seconds_bucket{job=\"api\"}[1m])))"
    ),
    doc = "RED at many layers > Why Duration differs"
  )

  private fun lbNoHealthy() = Scenario(
    id = "lb_no_healthy",
    title = "The LB answers 503 itself",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("prob" to round2(random.nextDouble(0.45, 0.6))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("health_fail_prob" to p.double("prob")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, users get scattered 503s. The api team insists their APM shows no errors at all." },
    hints = listOf(
      "Which layer shows the errors first? Compare 5xx at the client, at the LB and at api.",
      "Compare the 5xx the LB returns to clients with the 5xx api records itself. Who produces the difference?",
      "Look at Envoy's healthy upstream count and the request rate reaching api. What does api's /health return?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("lb", "api"), signal = setOf("lb_vs_api_errors"), cause = "lb_no_healthy"),
    explain = { p ->
      "api's /health answers 503 with probability ${p.percent("prob")}. Envoy marks the backend unhealthy after 2 consecutive failures and, " +
        "with panic mode off, answers 503 'no healthy upstream' itself during those windows. Requests never reach api, so APM sees a lower rate, " +
        "not errors. This is 'errors the LB produces itself' from RED at many layers."
    },
    evidence = listOf(
      "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"5\",envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[1m])) or vector(0)",
      "sum(rate(envoy_cluster_upstream_cx_none_healthy{envoy_cluster_name=\"api\"}[1m]))",
      "envoy_cluster_membership_healthy{envoy_cluster_name=\"api\"}",
      "sum(rate(http_server_requests_total{job=\"api\"}[1m]))"
    ),
    doc = "RED at many layers > Why Errors differ"
  )

  private fun downstreamSlow() = Scenario(
    id = "downstream_slow",
    title = "A slow downstream",
    level = 1,
    topics = listOf("layers", "red"),
    // Half the runs slow a fraction of the calls (the tail moves), half slow every call (p50 moves too).
    params = { random, _ ->
      val tail = random.nextBoolean()
      mapOf("tail" to tail, "extra" to random.nextInt(250, 451), "frac" to if (tail) round2(random.nextDouble(0.3, 0.6)) else 1.0)
    },
    faults = { p ->
      val pricing = if (p.bool("tail")) {
        mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to p.double("frac"))
      } else {
        mapOf("price_base_ms" to PRICE_BASE_MS + p.int("extra"))
      }
      FaultSpec(services = mapOf("pricing" to pricing))
    },
    decoy = 0.7,
    ticket = { p -> "At ${p.startedAt}, /checkout p99 is several times higher. /products is still normal." },
    hints = listOf(
      "On which route did duration rise? Did the rate change?",
      "Inside /checkout, which downstream rose with it: the DB, pricing or Kafka?",
      "Compare http_client_duration_seconds (api calling pricing) with pricing's own http_server_duration_seconds."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), signal = setOf("api_vs_downstream"), cause = "downstream_slow"),
    explain = { p ->
      val who = if (p.bool("tail")) "About ${p.percent("frac")} of pricing's /price requests get" else "Every one of pricing's /price requests gets"
      val shape = if (p.bool("tail")) "only the tail moves, p50 stays put" else "p50 moves as much as p99"
      "$who about ${p.int("extra")} ms of extra latency, so $shape. api's /checkout duration rises by exactly " +
        "the pricing call span; pricing's own RED confirms the latency rose on its server side, so it is neither the network nor api's pool. " +
        "pricing's CPU and event loop are quiet: it waits on something, it does not compute. The next step is latency analysis inside pricing."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, route) (rate(http_client_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))",
      "histogram_quantile(0.5, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))"
    ),
    doc = "RED at many layers > Compare two adjacent layers"
  )

  private fun downstreamErrors() = Scenario(
    id = "downstream_errors",
    title = "A downstream returns errors",
    level = 1,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("err" to round2(random.nextDouble(0.1, 0.25))) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("error_rate" to p.double("err")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, some checkouts fail with a 502. The mobile team says they are not retrying. /products is fine." },
    hints = listOf(
      "Errors at the client, at the LB and at api: are they the same count? What about attempts per request?",
      "api's /checkout errors: which downstream call fails? Look at http_client_requests_total by outcome.",
      "pricing's own RED: status codes on /price."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), signal = setOf("api_vs_downstream"), cause = "downstream_errors"),
    explain = { p ->
      "pricing answers 503 for about ${p.percent("err")} of /price calls. api turns each one into a 502 on /checkout, and the client records " +
        "the error once: attempts per request stay at 1, nothing is amplified, and the rate at every layer is flat. " +
        "Compare with the retry storm: same failing downstream, but there the client multiplies the attempts. " +
        "Errors pass through the layers unchanged here, so the first layer that produces them is the one to fix."
    },
    evidence = listOf(
      "sum by (outcome) (rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "sum by (status) (rate(http_server_requests_total{job=\"pricing\"}[1m]))",
      "sum(rate(client_attempts_total{endpoint=\"checkout\"}[1m])) / sum(rate(client_requests_total{endpoint=\"checkout\"}[1m]))"
    ),
    doc = "RED at many layers > Why Errors differ"
  )

  private fun apiTimeout() = Scenario(
    id = "api_timeout",
    title = "api times out on pricing before pricing answers",
    level = 3,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("timeout_s" to round2(random.nextDouble(0.2, 0.3)), "extra" to random.nextInt(320, 421), "frac" to 0.5) },
    faults = { p ->
      FaultSpec(
        services = mapOf(
          "api" to mapOf("pricing_timeout_s" to p.double("timeout_s")),
          "pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to p.double("frac"))
        )
      )
    },
    marker = { "Deploy pricing v3.5: rules engine upgraded" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, about half of the checkouts fail with a 502. The pricing team says every one of their requests returns 200, " +
        "just a little slower than before."
    },
    hints = listOf(
      "Errors at the client, at the LB and at api are the same. Which downstream call fails inside api?",
      "http_client_requests_total by outcome: is it a 5xx from pricing, or a timeout on api's side?",
      "Compare api's timeout towards pricing with pricing's p99 on /price."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api", "pricing"), signal = setOf("api_vs_downstream"), cause = "client_timeout", partial = setOf("downstream_slow")),
    explain = { p ->
      "pricing's /price got about ${p.int("extra")} ms slower on ${p.percent("frac")} of the calls, which is more than api's " +
        "${(p.double("timeout_s") * MILLIS_PER_SECOND).roundToInt()} ms client timeout. api gives up, records outcome=timeout and answers 502; " +
        "pricing finishes anyway and records a 200. The same timeout mismatch as the client timeout exercise, one hop deeper: here api is the client. " +
        "A 'client' is whoever sends the request on a given hop. Fix: set the timeout from the downstream's real p99 plus a margin, and fix the regression in pricing."
    },
    evidence = listOf(
      "sum by (outcome) (rate(http_client_requests_total{job=\"api\",target=\"pricing\"}[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"pricing\",route=\"/price\"}[1m])))",
      "sum by (status) (rate(http_server_requests_total{job=\"pricing\"}[1m]))"
    ),
    doc = "RED at many layers > Why Errors differ (mismatched timeouts)"
  )

  private fun pricingCpu() = Scenario(
    id = "pricing_cpu",
    title = "pricing saturates its single event loop thread",
    level = 2,
    topics = listOf("use", "runtime", "layers"),
    params = { random, _ -> mapOf("burn" to random.nextInt(42, 56)) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("cpu_ms" to p.int("burn")))) },
    marker = { "Deploy pricing v3.6: discount rules evaluated in memory" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 is several times higher and /products is normal. " +
        "The pricing team says their container uses less than 1 of its 2 cores."
    },
    hints = listOf(
      "Inside /checkout, which downstream rose? Compare api's client duration to pricing with pricing's own p99.",
      "USE for pricing: process CPU, event loop lag, throttling.",
      "pricing serves on one event loop thread. How many cores can it use?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), signal = setOf("one_core_event_loop"), cause = "single_thread_cpu", partial = setOf("downstream_slow")),
    explain = { p ->
      val cores = CHECKOUTS_PER_SECOND * p.int("burn") / MILLIS_PER_SECOND
      "v3.6 burns about ${p.int("burn")} ms of CPU per /price on pricing's single event loop thread. At about $CHECKOUTS_PER_SECOND calls/s " +
        "that is about ${p.fixed(cores, 2)} cores on one thread: the loop is saturated, event loop lag climbs and every /price waits its turn. " +
        "The container is not throttled and uses about one of its two cores, so a bigger limit changes nothing. " +
        "The slow downstream exercise stops at 'pricing is slow'; here USE on pricing names the resource."
    },
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"pricing\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"pricing\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core); USE at many layers > A service as a resource"
  )

  private fun cacheMiss() = Scenario(
    id = "cache_miss",
    title = "Cache misses push the load onto the DB",
    level = 2,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("ttl" to random.nextInt(100, 401)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("cache_ttl_ms" to p.int("ttl")))) },
    marker = { "Deploy checkout-api v2.34: configuration moved to the new config service" },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, Postgres CPU is up and the DBA asks who is firing queries at the products table. API traffic is unchanged." },
    hints = listOf(
      "Which query grew in Postgres? Did the API rate grow with it?",
      "What does a /products request pass through before it reaches the DB?",
      "Look at the cache hit ratio."
    ),
    answer = Answer(nature = setOf("amplified", "arch"), location = setOf("cache", "api"), signal = setOf("cache_hit_ratio", "query_rate_by_name"), cause = "cache_miss"),
    explain = { p ->
      "The cache TTL was set to ${p.int("ttl")} ms, so almost every /products misses and runs the product_detail query (a scan over reviews). " +
        "The API rate is flat, the product_detail query rate at the DB climbs, and /products latency follows. " +
        "This is rate rising at a lower layer because of the cache, not because of users. The missing index on reviews was always there; " +
        "the cache is what kept it cheap, and the cache is what changed."
    },
    evidence = listOf(
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs (cache); Combining with other methods > Methods from chapter 2 (Cache tuning)"
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
    evidence = listOf(
      "sum by (outcome) (rate(client_requests_total{endpoint=\"checkout\"}[1m]))",
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))"
    ),
    doc = "RED at many layers > Why Errors differ (mismatched timeouts)"
  )

  // ---------------------------------------------------------------- async
  private fun consumerBacklog() = Scenario(
    id = "consumer_backlog",
    title = "The consumer cannot keep up",
    level = 2,
    topics = listOf("async", "load"),
    params = { random, _ -> mapOf("extra" to random.nextInt(170, 241)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("extra_events_rps" to p.int("extra")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the reporting team says new orders take minutes to show up (normally seconds). The worker shows no errors." },
    hints = listOf(
      "The worker's RED: consume rate, errors, processing time. Did the processing time change?",
      "Compare the produce rate with the consume rate. How is lag moving over time?",
      "Split the produce rate by source."
    ),
    answer = Answer(nature = setOf("load"), location = setOf("worker"), signal = setOf("consumer_lag"), cause = "consumer_backlog"),
    explain = { p ->
      "A backfill job pushes about ${p.int("extra")} extra events/s into the orders topic. The worker processes each partition sequentially, " +
        "about 25 ms per message, so its capacity is about $WORKER_CAPACITY_PER_SECOND msg/s across 3 partitions. The produce rate exceeds that capacity: " +
        "processing time is flat and errors are zero, but lag in messages and in seconds grows steadily. " +
        "Lag is the consumer group's saturation; end-to-end latency is what users see."
    },
    evidence = listOf(
      "sum by (source) (rate(kafka_produced_total[1m]))",
      "sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "max(kafka_consumer_lag_seconds)"
    ),
    doc = "RED at many layers > The async layer"
  )

  private fun consumerSlow() = Scenario(
    id = "consumer_slow",
    title = "The worker got slower per message",
    level = 2,
    topics = listOf("async", "layers"),
    params = { random, _ -> mapOf("process_ms" to random.nextInt(260, 331)) },
    faults = { p -> FaultSpec(services = mapOf("worker" to mapOf("process_ms" to p.int("process_ms")))) },
    marker = { "Deploy order-worker v1.9: fraud check added to each order" },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the reporting team says new orders take minutes to show up. Order volume is normal and the worker shows no errors." },
    hints = listOf(
      "The worker's RED: consume rate, errors, processing time per message. Which one moved?",
      "Compare the produce rate with the consume rate. Did the producer grow, or did the consumer shrink?",
      "Capacity = partitions / processing time. With 3 partitions, what is it now against about $CHECKOUTS_PER_SECOND msg/s coming in?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), signal = setOf("consumer_lag"), cause = "consumer_slow", partial = setOf("consumer_backlog")),
    explain = { p ->
      val capacity = PARTITIONS * MILLIS_PER_SECOND / p.int("process_ms")
      "v1.9 spends about ${p.int("process_ms")} ms per message instead of 25. Each partition runs one message at a time, so the group's capacity " +
        "dropped from about $WORKER_CAPACITY_PER_SECOND msg/s to about ${p.fixed(capacity, 0)} msg/s, below the normal $CHECKOUTS_PER_SECOND msg/s of orders. " +
        "The produce rate is flat and errors are zero, but the consume rate is pinned at capacity and lag grows on every partition. " +
        "Same symptom as the backlog exercise, opposite cause: there the producer grew, here the consumer shrank. " +
        "Processing time per message is the metric that tells them apart."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(kafka_processing_seconds_bucket[1m])))",
      "sum(rate(kafka_produced_total[1m])) and sum(rate(kafka_consumed_total{outcome=\"ok\"}[1m]))",
      "max(kafka_consumer_lag_seconds)"
    ),
    doc = "RED at many layers > The async layer"
  )

  private fun poisonMessage() = Scenario(
    id = "poison_message",
    title = "Poison message",
    level = 3,
    topics = listOf("async"),
    params = { random, _ -> mapOf("nonce" to String.format("%06x", random.nextInt(NONCE_SPACE))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("poison_nonce" to p.string("nonce")), "worker" to mapOf("fail_nonce" to p.string("nonce")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, some orders are never processed while others are fine. The worker's total throughput is almost unchanged." },
    hints = listOf(
      "Look at lag per partition instead of the total.",
      "Are the worker's errors and retries up? On which partition?",
      "Read the worker log: make logs S=worker"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("worker"), signal = setOf("lag_per_partition"), cause = "poison_message", partial = setOf("consumer_backlog")),
    explain = { p ->
      "One bad event (currency 'XXX', nonce ${p.string("nonce")}) makes the worker fail; it retries every second and never skips the message. " +
        "Because each partition is processed in order, the whole partition holding it is blocked: exactly one partition's lag grows without end " +
        "while the others keep moving, so the total throughput barely changes. Fix: cap the retries, then send the message to a DLQ."
    },
    evidence = listOf(
      "kafka_consumer_lag_messages",
      "sum by (partition) (rate(kafka_consumer_retries_total[1m]))"
    ),
    doc = "RED at many layers > The async layer (poison message)"
  )

  // ---------------------------------------------------------------- DB
  private fun dbLock() = Scenario(
    id = "db_lock",
    title = "Lock contention on one hot row",
    level = 3,
    topics = listOf("use", "db", "layers"),
    // Each checkout holds the hot row for hold + about 5 ms; above about 61 ms, 15 checkouts/s exceed what one row lock can serve.
    params = { random, _ -> mapOf("hold" to random.nextInt(70, 86)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("hot_row" to true, "lock_hold_ms" to p.int("hold")))) },
    marker = { "Deploy checkout-api v2.33: reserve inventory when placing an order" },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, /checkout is slow with 503s. The DB team reports that query execution time inside Postgres went up." },
    hints = listOf(
      "Which query's exec time rose inside Postgres?",
      "Exec time is up, but what is Postgres doing? Look at pg_lock_waiters and connections by state.",
      "Does every checkout update the same row?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("db"), signal = setOf("pg_locks"), cause = "db_lock", partial = setOf("pool_exhausted")),
    explain = { p ->
      "v2.33 makes every checkout update the same inventory row 'HOT-1' and then holds the transaction about ${p.int("hold")} ms longer. " +
        "Transactions queue on the row lock: update_inventory exec time inside Postgres rises (the lock wait counts as exec time), pg_lock_waiters > 0. " +
        "Because connections are held while waiting for the lock, api's pool drains too, but that is a consequence. " +
        "Unlike the pool exercise, here Postgres itself reports the higher exec time."
    },
    evidence = listOf(
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "pg_lock_waiters",
      "pg_connections"
    ),
    doc = "USE method in detail > Software resources (mutex, lock); End-to-end investigation example (compare with it)"
  )

  // ---------------------------------------------------------------- memory / host
  private fun oom() = Scenario(
    id = "oom",
    title = "Periodic OOM kill",
    level = 3,
    topics = listOf("use", "container"),
    params = { random, _ -> mapOf("leak" to random.nextInt(MIN_LEAK_KB, MAX_LEAK_KB + 1)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("leak_kb" to p.int("leak")))) },
    decoy = 0.5,
    ticket = { p -> "Since ${p.startedAt}, about once a minute there is a burst of 503s that lasts a few seconds and then clears. Nobody deployed anything." },
    hints = listOf(
      "During the errors, is api still receiving requests? Look at process_start_time_seconds (the restart count).",
      "What does the api container's memory look like right before each restart, against its limit?",
      "cgroup_memory_usage_bytes against cgroup_memory_limit_bytes for api."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("container", "api"), signal = setOf("memory_restarts"), cause = "oom"),
    explain = { p ->
      "Each request keeps about ${p.int("leak")} KB for ever (a memory leak). At about 50 requests/s memory grows by a few MB per second until it " +
        "reaches the container's 512 MB limit: the kernel OOM-kills the process, Docker restarts api, and Envoy answers 503 while api is down. " +
        "These are errors at the cgroup layer (USE). The sawtooth memory curve and the restart count are the main evidence."
    },
    evidence = listOf(
      "cgroup_memory_usage_bytes{job=\"api\"} / cgroup_memory_limit_bytes{job=\"api\"}",
      "changes(process_start_time_seconds{job=\"api\"}[10m])",
      "increase(node_vmstat_oom_kill[10m])"
    ),
    doc = "USE at many layers > Layers and their metrics (container, cgroup)"
  )

  private fun noisyNeighbor() = Scenario(
    id = "noisy_neighbor",
    title = "Noisy neighbour on the host",
    level = 2,
    topics = listOf("use", "host"),
    params = { _, context -> mapOf("burners" to maxOf(MIN_BURNERS, 2 * (context.cpuCount ?: DEFAULT_CPU_COUNT))) },
    faults = { p -> FaultSpec(services = mapOf("noisy" to mapOf("burners" to p.int("burners")))) },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, every service is slower, including pricing and the worker. The rate is normal and nobody deployed anything." },
    hints = listOf(
      "When everything slows down at once, think of the shared resources.",
      "USE at the host / VM layer: CPU by mode, load against the CPU count, CPU PSI.",
      "Which container uses the most CPU? Compare cgroup_cpu_usage_seconds_total by job."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("host"), signal = setOf("host_cpu"), cause = "noisy_neighbor"),
    explain = { p ->
      "The 'noisy' container (a batch job sharing the machine, given a high CPU priority through cpu_shares) runs ${p.int("burners")} CPU-burning threads. " +
        "Host / VM CPU is near 100%, load is far above the CPU count, CPU PSI rises. Every service slows down because they all compete for CPU. " +
        "None of your services is throttled or failing: the cause sits in the shared layer."
    },
    evidence = listOf(
      "sum by (mode) (rate(node_cpu_seconds_total{mode!=\"idle\"}[1m])) / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "node_load1 / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "sum by (job) (rate(cgroup_cpu_usage_seconds_total[1m]))"
    ),
    doc = "USE at many layers > Layers and their metrics (hypervisor, host)"
  )

  private fun round2(value: Double): Double = (value * HUNDREDTHS).roundToInt() / HUNDREDTHS

  private const val HUNDREDTHS = 100.0
  private const val PERCENT = 100
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val DEFAULT_PRODUCT_DETAIL_SECONDS = 0.012
  private const val CRAWLER_CPU_BUDGET = 1.8
  private const val MIN_CRAWLER_RPS = 40
  private const val MAX_CRAWLER_RPS = 600

  private const val AVERAGE_BASKET = 2.5
  private const val BASE_CHECKOUT_HOLD_MS = 25
  private const val PRICE_BASE_MS = 12
  private const val MIN_SURGE = 4.0
  private const val MAX_SURGE = 5.0
  private const val MIN_CHECKOUT_SHARE = 0.65
  private const val MAX_CHECKOUT_SHARE = 0.75

  // A JVM needs a little more than the Python service did to keep answering at all under a tiny quota.
  private const val MIN_THROTTLE_CPUS = 0.25
  private const val MAX_THROTTLE_CPUS = 0.4
  private const val CFS_PERIOD_MS = 100
  private const val NONCE_SPACE = 0x1000000
  private const val MIN_BURNERS = 4
  private const val PARTITIONS = 3
  private const val WORKER_CAPACITY_PER_SECOND = 120

  // api idles near 300 of its 512 MB, so at 50 requests/s this leak reaches the limit about once a minute.
  private const val MIN_LEAK_KB = 90
  private const val MAX_LEAK_KB = 130
  private const val DEFAULT_CPU_COUNT = 4
}

package com.c0x12c.incidentlab.labctl.scenario

/** The four multiple-choice questions a learner answers, with the stable keys the catalog uses. */
object Answers {
  val NATURES: List<Pair<String, String>> = listOf(
    "load" to "Real load change: demand from users or an external source went up or down",
    "arch" to "Not load: the system itself got slower or started failing (code, config, resource)",
    "amplified" to "Rate rose at one layer but it is not real demand (retry, fan-out, cache miss)"
  )

  val LOCATIONS: List<Pair<String, String>> = listOf(
    "client" to "Client side / the load source",
    "lb" to "Load balancer, or a queue before the app accepts the request",
    "api" to "Inside api (code, runtime, api's software resources)",
    "api_db" to "The api -> Postgres leg, on the DB client side (pool, query count)",
    "db" to "Inside Postgres",
    "pricing" to "The pricing service",
    "cache" to "Redis / cache",
    "worker" to "The Kafka consumer (worker)",
    "network" to "The network between two services",
    "disk" to "Disk I/O under Postgres or the host",
    "container" to "The api container / cgroup",
    "host" to "The shared host / VM"
  )

  /** The one comparison or panel that separates the cause from its look-alikes. */
  val SIGNALS: List<Pair<String, String>> = listOf(
    "client_rate_by_tenant" to "Client rate split by tenant: one grew, all grew, or one vanished",
    "rate_by_route" to "Rate split by route: the mix moved while the total stayed flat",
    "attempts_vs_requests" to "Attempts per user request at the client (retries)",
    "client_errors_vs_server" to "Client timeouts against server-side 200s and the server p99",
    "lb_vs_api_errors" to "5xx at the LB against 5xx at api, the rate reaching api, healthy upstreams",
    "lb_vs_api_duration" to "LB p99 against api p99: the gap is time spent before the app",
    "api_vs_downstream" to "api's client view of pricing (duration, outcome) against pricing's own RED",
    "one_core_event_loop" to "Process CPU near one core and event loop lag (api or pricing)",
    "pool" to "Pool in use against max, waiting, acquire wait; DB session time against Postgres exec time",
    "query_rate_by_name" to "Query rate per query name against the API rate",
    "cache_hit_ratio" to "Cache hit ratio",
    "pg_locks" to "pg_lock_waiters and Postgres connections by state",
    "cgroup_cpu" to "cgroup CPU usage against the limit, and the share of throttled periods",
    "memory_restarts" to "Container memory against its limit (sawtooth) and the restart count",
    "host_cpu" to "Host CPU and load, split by container",
    "consumer_lag" to "Produce rate against consume rate, processing time, lag over time",
    "lag_per_partition" to "Lag and retries per partition"
  )

  /** Causes by layer, outermost first. The prompt prints one header per layer. */
  private val CAUSES_BY_LAYER: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Client and load" to listOf(
      "tenant_spike" to "Traffic surge from one client group / tenant",
      "global_load" to "Traffic grew across every client (campaign, peak hour)",
      "workload_mix" to "Same total rate, but the mix moved to heavier requests",
      "client_outage" to "A client stopped sending requests (crash, bad release, DNS)",
      "retry_storm" to "Retry storm: client retries multiply the requests",
      "client_timeout" to "The caller's timeout is shorter than the server's latency"
    ),
    "Before the app" to listOf(
      "queue_before_app" to "Requests queue before the app accepts them (concurrency / workers too low)",
      "lb_no_healthy" to "The load balancer answers 503 itself because no backend is healthy"
    ),
    "Inside the app" to listOf(
      "single_thread_cpu" to "One core saturated: CPU-bound code on a single-threaded event loop",
      "n_plus_one" to "N+1 queries: each request sends far too many queries",
      "cache_miss" to "Cache misses rose, load shifted to the DB",
      "gc_pause" to "Long GC pauses stop the JVM",
      "memory_leak" to "Memory grows without limit (a leak) and will hit the limit"
    ),
    "DB client and Postgres" to listOf(
      "pool_exhausted" to "Connection pool exhausted: hold time too long, or the pool too small",
      "pool_timeout_short" to "The pool's acquire timeout is too short: requests give up in short bursts",
      "db_lock" to "Lock contention in Postgres",
      "missing_index" to "A missing index makes one query scan the whole table"
    ),
    "Downstream service" to listOf(
      "downstream_slow" to "A downstream service is slow",
      "downstream_errors" to "A downstream service returns errors"
    ),
    "Async" to listOf(
      "consumer_backlog" to "The producer sends more than the consumer can handle, backlog grows",
      "consumer_slow" to "The consumer got slower per message, throughput dropped",
      "poison_message" to "A poison message blocks one partition",
      "slow_drain" to "The fix worked, but the backlog drains slowly: too little headroom"
    ),
    "Network" to listOf(
      "packet_loss" to "Packet loss or a slow network between services"
    ),
    "Disk and storage" to listOf(
      "disk_full" to "Disk full or disk I/O saturated on the DB host"
    ),
    "Container and host" to listOf(
      "cpu_throttle" to "CPU throttling from the container's CPU limit",
      "oom" to "Memory limit hit: OOM kill, then restart",
      "noisy_neighbor" to "Another process takes the host's CPU"
    )
  )

  val CAUSES: List<Pair<String, String>> = CAUSES_BY_LAYER.flatMap { it.second }

  val CAUSE_GROUPS: Map<String, String> = CAUSES_BY_LAYER.flatMap { (group, causes) -> causes.map { it.first to group } }.toMap()

  /** Causes no scenario has. They keep the cause list from being a copy of the catalog. */
  val DISTRACTORS: Set<String> = setOf("gc_pause", "missing_index", "packet_loss", "disk_full")

  val DECOYS: List<String> = listOf(
    "Deploy worker v1.8: logging library update",
    "Deploy pricing v3.4: log format change",
    "Feature flag: promo banner enabled on web",
    "Deploy loadgen-ios v5.2: display bug fix"
  )

  val TOPICS: List<String> = listOf(
    "red", "load", "layers", "use", "container", "runtime", "db", "async", "host", "little", "cpu", "jvm", "disk", "network", "profiling", "capacity"
  )

  fun label(options: List<Pair<String, String>>, key: String): String = options.first { it.first == key }.second
}

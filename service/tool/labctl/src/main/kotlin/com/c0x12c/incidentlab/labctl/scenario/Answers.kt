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

  /** The one comparison or panel that separates the cause from its look-alikes. A label names what to compare, never what it showed. */
  val SIGNALS: List<Pair<String, String>> = listOf(
    "client_rate_by_tenant" to "Client rate split by tenant",
    "rate_by_route" to "Rate split by route, against the total rate",
    "attempts_vs_requests" to "Attempts sent against user requests at the client",
    "client_errors_vs_server" to "Errors the client sees against the status codes and p99 the server records",
    "lb_vs_api_errors" to "5xx at the LB against 5xx at api",
    "lb_vs_api_duration" to "LB p99 against api p99",
    "api_vs_downstream" to "api's view of pricing (duration, outcome) against pricing's own RED",
    "one_core_event_loop" to "Process CPU in cores and event loop lag (api or pricing)",
    "pool" to "Pool in use against max, waiting and acquire wait; DB session time against Postgres exec time",
    "query_rate_by_name" to "Query rate per query name against the API rate",
    "cache_hit_ratio" to "Cache hit ratio against the product query rate at Postgres",
    "pg_locks" to "pg_lock_waiters and Postgres connections by state",
    "cgroup_cpu" to "cgroup CPU usage against the limit, and the share of throttled periods",
    "memory_restarts" to "Container memory against its limit, and the restart count",
    "host_cpu" to "Host CPU and load, split by container",
    "consumer_lag" to "Produce rate against consume rate, processing time, lag over time",
    "lag_per_partition" to "Lag and retries per partition",
    "worker_cpu_vs_lag" to "Worker process CPU against its lag and consume rate",
    "lock_wait" to "Lock wait time and waiting threads inside api",
    "cpu_profile" to "A CPU profile of api over the incident window",
    "tcp_retransmits" to "TCP retransmits and RTO timeouts per service, against pricing's own p99",
    "tcp_connections" to "New TCP connections per second and the TIME_WAIT count at api, against its call rate",
    "disk_io" to "Host disk await and busy share against Postgres wait events and exec time",
    "jvm_gc" to "GC pause rate and pause p99 against live data after GC and the heap max",
    "vthread_pinned" to "Pinned virtual-thread events per second against in-flight requests",
    "user_vs_sys" to "Process CPU split into user and system time"
  )

  /** Causes by layer, outermost first. The prompt prints one header per layer. */
  private val CAUSES_BY_LAYER: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Client and load" to listOf(
      "tenant_spike" to "Traffic surge from one client group / tenant",
      "global_load" to "Real traffic changed across every client (campaign, peak hour, quiet day)",
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
      "memory_leak" to "Memory grows without limit (a leak) and will hit the limit",
      "app_lock" to "A lock inside the app serializes requests: they wait for each other, not for a resource",
      "hot_code_path" to "A hot code path burns CPU on every request: a serializer, a regex, a formatter",
      "thread_pool_exhausted" to "A thread pool is full: requests wait for a free worker thread",
      "vthread_pinning" to "Virtual threads pinned to their carriers: synchronized around blocking work caps concurrency at the CPU count",
      "sync_logging" to "Verbose synchronous logging: every line is a write syscall under one lock"
    ),
    "DB client and Postgres" to listOf(
      "pool_exhausted" to "Connection pool exhausted: hold time too long, or the pool too small",
      "pool_timeout_short" to "The pool's acquire timeout is shorter than a normal wait for a connection",
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
      "slow_drain" to "The backlog drains slowly: the consumer has little headroom over the produce rate",
      "busy_poll" to "The consumer polls with no wait and spins on an empty queue",
      "partition_skew" to "One partition gets most of the messages: a hot key"
    ),
    "Network" to listOf(
      "packet_loss" to "Packet loss between two services: a lost segment waits for a retransmit",
      "rtt_amplified" to "A small network delay multiplied by many sequential round trips per request",
      "no_keepalive" to "Connections are not reused: every call pays a handshake and leaves a TIME_WAIT socket",
      "dns_slow" to "Slow or failing DNS lookups"
    ),
    "Disk and storage" to listOf(
      "disk_full" to "Disk full: writes fail with 'No space left on device'",
      "disk_io_contention" to "Another process saturates the disk Postgres writes its WAL to",
      "checkpoint_bursts" to "Checkpoints write their dirty pages in short bursts: periodic I/O spikes",
      "iops_limit" to "The volume hit its IOPS limit: a quota sits between the database and an idle disk"
    ),
    "Container and host" to listOf(
      "cpu_throttle" to "CPU throttling from the container's CPU limit",
      "oom" to "Memory limit hit: OOM kill, then restart",
      "noisy_neighbor" to "Another process takes the host's CPU",
      "cpu_credits" to "A burstable VM ran out of CPU credits and fell to its baseline CPU"
    )
  )

  val CAUSES: List<Pair<String, String>> = CAUSES_BY_LAYER.flatMap { it.second }

  val CAUSE_GROUPS: Map<String, String> = CAUSES_BY_LAYER.flatMap { (group, causes) -> causes.map { it.first to group } }.toMap()

  /** Causes no scenario has. They keep the cause list from being a copy of the catalog. */
  val DISTRACTORS: Set<String> = setOf("thread_pool_exhausted", "missing_index", "partition_skew", "disk_full", "dns_slow", "disk_io_contention", "checkpoint_bursts")

  val DECOYS: List<String> = listOf(
    "Deploy worker v1.8: logging library update",
    "Deploy pricing v3.4: log format change",
    "Feature flag: promo banner enabled on web",
    "Deploy loadgen-ios v5.2: display bug fix"
  )

  val TOPICS: List<String> = listOf(
    "red", "load", "layers", "use", "container", "runtime", "db", "async", "host", "little", "cpu", "jvm", "disk", "network", "profiling", "capacity"
  )

  /** The causes a ticket with each symptom could have: its real look-alikes, and at least one distractor. */
  internal val CAUSES_BY_SYMPTOM: Map<Symptom, Set<String>> = mapOf(
    Symptom.WORK_LATE to setOf(
      "consumer_backlog", "consumer_slow", "poison_message", "slow_drain", "partition_skew", "busy_poll", "db_lock", "iops_limit", "disk_io_contention"
    ),
    Symptom.CHANGE_TO_JUDGE to setOf(
      "cpu_credits", "cpu_throttle", "noisy_neighbor", "downstream_slow", "downstream_errors", "client_timeout", "no_keepalive", "rtt_amplified", "packet_loss",
      "dns_slow"
    ),
    Symptom.ERRORS_UNSEEN to setOf("lb_no_healthy", "client_timeout", "downstream_slow", "downstream_errors", "oom", "packet_loss", "dns_slow", "retry_storm"),
    Symptom.ERRORS to setOf(
      "oom", "pool_exhausted", "pool_timeout_short", "db_lock", "missing_index", "downstream_errors", "retry_storm", "global_load", "lb_no_healthy", "disk_full"
    ),
    Symptom.TAIL_ONLY to setOf(
      "gc_pause", "memory_leak", "downstream_slow", "packet_loss", "cpu_throttle", "noisy_neighbor", "queue_before_app", "checkpoint_bursts", "dns_slow"
    ),
    Symptom.SLOW_BUT_API_FINE to setOf("queue_before_app", "global_load", "tenant_spike", "gc_pause", "packet_loss", "noisy_neighbor", "dns_slow"),
    Symptom.SLOW_EVERY_ROUTE to setOf(
      "single_thread_cpu", "cpu_throttle", "noisy_neighbor", "vthread_pinning", "app_lock", "hot_code_path", "sync_logging", "rtt_amplified", "n_plus_one",
      "pool_exhausted", "thread_pool_exhausted"
    ),
    Symptom.SLOW_ONE_ROUTE to setOf(
      "app_lock", "pool_exhausted", "iops_limit", "downstream_slow", "single_thread_cpu", "cache_miss", "tenant_spike", "global_load", "noisy_neighbor",
      "disk_io_contention"
    ),
    Symptom.RATE_CHANGED to setOf(
      "n_plus_one", "cache_miss", "workload_mix", "client_outage", "retry_storm", "client_timeout", "downstream_slow", "global_load", "missing_index", "dns_slow"
    ),
    Symptom.RESOURCE_ALARM to setOf(
      "busy_poll", "memory_leak", "oom", "hot_code_path", "sync_logging", "gc_pause", "consumer_backlog", "consumer_slow", "noisy_neighbor", "disk_full"
    )
  )

  /** The signals that tell apart the causes of each symptom. */
  internal val SIGNALS_BY_SYMPTOM: Map<Symptom, Set<String>> = mapOf(
    Symptom.WORK_LATE to setOf("consumer_lag", "lag_per_partition", "worker_cpu_vs_lag", "pg_locks", "disk_io"),
    Symptom.CHANGE_TO_JUDGE to setOf("cgroup_cpu", "api_vs_downstream", "tcp_connections", "tcp_retransmits", "client_errors_vs_server", "host_cpu"),
    Symptom.ERRORS_UNSEEN to setOf("lb_vs_api_errors", "client_errors_vs_server", "api_vs_downstream", "memory_restarts", "attempts_vs_requests", "tcp_retransmits"),
    Symptom.ERRORS to setOf("memory_restarts", "pool", "pg_locks", "api_vs_downstream", "attempts_vs_requests", "lb_vs_api_errors", "client_rate_by_tenant"),
    Symptom.TAIL_ONLY to setOf("jvm_gc", "api_vs_downstream", "tcp_retransmits", "cgroup_cpu", "lb_vs_api_duration", "disk_io"),
    Symptom.SLOW_BUT_API_FINE to setOf("lb_vs_api_duration", "client_rate_by_tenant", "jvm_gc", "tcp_retransmits", "host_cpu"),
    Symptom.SLOW_EVERY_ROUTE to setOf("one_core_event_loop", "cgroup_cpu", "host_cpu", "vthread_pinned", "cpu_profile", "user_vs_sys", "pool", "lock_wait"),
    Symptom.SLOW_ONE_ROUTE to setOf(
      "lock_wait", "pool", "disk_io", "api_vs_downstream", "one_core_event_loop", "cache_hit_ratio", "query_rate_by_name", "client_rate_by_tenant", "host_cpu"
    ),
    Symptom.RATE_CHANGED to setOf("query_rate_by_name", "cache_hit_ratio", "rate_by_route", "client_rate_by_tenant", "attempts_vs_requests", "client_errors_vs_server"),
    Symptom.RESOURCE_ALARM to setOf("worker_cpu_vs_lag", "memory_restarts", "cpu_profile", "user_vs_sys", "jvm_gc", "consumer_lag")
  )

  /** The ticket symptoms each cause can produce. An exercise offers a cause only when its ticket shows one of them. */
  val CAUSE_SYMPTOMS: Map<String, Set<Symptom>> = CAUSES.associate { (key, _) -> key to CAUSES_BY_SYMPTOM.filterValues { key in it }.keys }

  /** The ticket symptoms each signal can tell apart. An exercise offers a signal only when its ticket shows one of them. */
  val SIGNAL_SYMPTOMS: Map<String, Set<Symptom>> = SIGNALS.associate { (key, _) -> key to SIGNALS_BY_SYMPTOM.filterValues { key in it }.keys }

  /** For each cause, what you would see if it were the one: the check that rules it in or out. Shown only after the answer. */
  val RULE_OUTS: Map<String, String> = mapOf(
    "tenant_spike" to "Client requests/s split by tenant: one tenant grows while the others stay flat.",
    "global_load" to "Client requests/s moves for every tenant at once, and api's rate moves with it.",
    "workload_mix" to "The total rate stays flat while the split by route moves toward the heavy route.",
    "client_outage" to "One tenant's rate falls toward 0 at the client, and every service stays green.",
    "retry_storm" to "Attempts at the client grow faster than user requests. Each user request is sent more than once.",
    "client_timeout" to "The caller counts timeouts while the server records 200s for the same calls.",
    "queue_before_app" to "LB p99 rises far above api's own p99, and requests wait at api's admission gate.",
    "lb_no_healthy" to "The LB returns 5xx that api never records. Envoy counts requests that found no healthy backend.",
    "single_thread_cpu" to "The process uses close to 1 core and event loop lag rises. The CPU limit is not reached.",
    "n_plus_one" to "Queries per second for one query name grow to many times the /checkout rate.",
    "cache_miss" to "The cache hit ratio drops and product queries at Postgres rise. The API rate stays flat.",
    "gc_pause" to "GC pause p99 jumps. Live data after a full GC sits near the old gen max.",
    "memory_leak" to "Memory grows in a straight line and never comes back down.",
    "app_lock" to "Lock wait time and waiting threads rise. CPU and the pool stay low.",
    "hot_code_path" to "CPU rises with the same traffic, mostly as user time. One function of the app owns most of the CPU profile.",
    "thread_pool_exhausted" to "Busy threads sit at the pool max and the task queue grows, while CPU stays low.",
    "vthread_pinning" to "Pinned virtual-thread events rise and in-flight requests pile up. CPU stays low.",
    "sync_logging" to "System CPU time climbs close to user time, with the same traffic.",
    "pool_exhausted" to "Pool in use sits at the max and waiting rises. Postgres exec time stays flat.",
    "pool_timeout_short" to "Pool timeouts rise while pool use stays well under the max on average. The acquire wait p99 sits flat at the timeout.",
    "db_lock" to "pg_lock_waiters rises, and Postgres exec time per query grows.",
    "missing_index" to "One query is slow on every call inside Postgres, and its plan is a full table scan. Its call count did not change.",
    "downstream_slow" to "pricing's own p99 rises along with api's view of it.",
    "downstream_errors" to "pricing's own 5xx rise and match the errors api sees on its calls.",
    "consumer_backlog" to "The produce rate grows above what the worker can take. Processing time per message stays flat.",
    "consumer_slow" to "Processing time per message rises and the consume rate falls. The produce rate is normal.",
    "poison_message" to "Lag and retries grow on one partition only. The other partitions stay near 0.",
    "partition_skew" to "One partition gets most of the new messages. Its lag grows, but it has no retries.",
    "slow_drain" to "After the fix, the consume rate is only a little above the produce rate. So the lag falls slowly.",
    "busy_poll" to "Worker CPU sits near its limit. The lag stays at 0 and the consume rate is normal.",
    "packet_loss" to "TCP retransmits and RTO timeouts rise at the sender. The receiver's own p99 stays flat.",
    "rtt_amplified" to "Each query takes a few ms longer at api than inside Postgres. Requests make many round trips, and pool use rises.",
    "no_keepalive" to "New TCP connections per second equal calls per second. TIME_WAIT sockets pile up on the side that closes.",
    "dns_slow" to "Calls stall or fail before any connection opens. Calls by IP address are fine.",
    "disk_full" to "Writes fail with a no space left on device error. Free disk space is at 0.",
    "disk_io_contention" to "Host disk busy share and await rise. Another process writes to the same disk as Postgres.",
    "checkpoint_bursts" to "Write latency spikes line up with each checkpoint, then calm down until the next one.",
    "iops_limit" to "Postgres waits on I/O. The host disk shows a low busy share and a low await.",
    "cpu_throttle" to "The share of throttled periods climbs from 0 while the host has CPU to spare. The 1-minute CPU use can still look under the limit.",
    "oom" to "Memory climbs to the container limit. Then the process restarts and the OOM kill count rises.",
    "noisy_neighbor" to "Host CPU and load rise from another container, and every service slows at once.",
    "cpu_credits" to "Nothing changes at first. Then api's CPU ceiling drops with no deploy. On a real VM, steal time rises and the credit balance hits 0."
  )

  fun causesFor(symptom: Symptom): List<Pair<String, String>> = CAUSES.filter { symptom in CAUSE_SYMPTOMS.getValue(it.first) }

  fun signalsFor(symptom: Symptom): List<Pair<String, String>> = SIGNALS.filter { symptom in SIGNAL_SYMPTOMS.getValue(it.first) }

  fun label(options: List<Pair<String, String>>, key: String): String = options.first { it.first == key }.second
}

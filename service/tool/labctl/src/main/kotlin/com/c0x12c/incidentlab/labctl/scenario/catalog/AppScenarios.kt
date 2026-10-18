package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.AVERAGE_BASKET
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.DEFAULT_PRODUCT_DETAIL_SECONDS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PRODUCTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.ExerciseContext
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults inside api: its code, its runtime and its cache. */
internal object AppScenarios {

  val all: List<Scenario> = listOf(
    nPlusOne(),
    singleThreadCpu(),
    cacheMiss(),
    appLock(),
    forkDecoy()
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
    answer = Answer(nature = setOf("amplified", "arch"), location = setOf("api_db", "api"), signal = setOf("query_rate_by_name"), cause = "n_plus_one"),
    explain = { p ->
      val queries = (AVERAGE_BASKET * p.int("k")).roundToInt()
      "The new ORM loads prices one product at a time, ${p.int("k")} times per product (lazy loading). An average checkout of $AVERAGE_BASKET products " +
        "turns into about $queries queries instead of 1. The API rate is flat but the DB rate is up: the fan-out grew, not the users. " +
        "Each query is fast inside Postgres and /checkout gets only a little slower, so users barely notice: the cost lands on the DB as load. " +
        "Fix: load in batches (WHERE id = ANY(...)) or eager loading."
    },
    steps = listOf(
      "Problem statement: the DBA sees the query count multiplied, and marketing says there is no campaign.",
      "Compare the API rate with the query rate at Postgres: the API rate is flat and the query rate is up. The fan-out grew.",
      "Split the query rate by name: product_by_id is new or jumped.",
      "Divide product_by_id per second by /checkout per second: one checkout now sends many queries.",
      "Each query is fast, so /checkout barely slows. The cost lands on the DB as load.",
      "The cause: N+1 queries from the new ORM. Fix it with a batch load or eager loading."
    ),
    evidence = listOf(
      "sum by (query) (rate(db_client_queries_total{job=\"api\"}[1m]))",
      "sum(rate(db_client_queries_total{job=\"api\",query=\"product_by_id\"}[1m])) / sum(rate(http_server_requests_total{job=\"api\",route=\"/checkout\"}[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    cli = listOf(
      "for i in 1 2; do curl -s http://api:8000/metrics | grep -E '^(db_client_queries_total.*product_by_id|http_server_requests_total.*/checkout)'; sleep 10; done   # product_by_id grows many times faster than /checkout",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select calls, left(query, 60) as query from pg_stat_statements order by calls desc limit 5\"   # the product lookup leads by calls"
    ),
    doc = "RED at many layers > Why Rate differs (fan-out)"
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
        "to 4 cores does nothing. This is the classic single-threaded application case: the load is flat, the architecture is the limit."
    },
    steps = listOf(
      "Problem statement: /products and /checkout are slow, the rate is normal, and someone wants a 4-core limit.",
      "USE for the container: CPU is about 1 of 2 cores and there is no throttling, so a bigger limit changes nothing.",
      "api serves on one event loop thread. Look at process CPU and event loop lag.",
      "Process CPU sits near one core and event loop lag rises: one thread is saturated.",
      "Open a /products trace: the render span burns CPU. /checkout slows because it waits on the same loop.",
      "The cause: CPU work on a single-threaded event loop. The load is flat; the architecture is the limit."
    ),
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"api\"}[1m])))",
      "rate(cgroup_cpu_throttled_periods_total{job=\"api\"}[1m]) / rate(cgroup_cpu_periods_total{job=\"api\"}[1m])"
    ),
    cli = listOf(
      "pidstat -t -p $(pid api) 5 1 | grep Average | sort -k9 -nr | head -4   # one thread takes most of the process CPU, under one core",
      "cat $(cg $(pid api))/cpu.max; grep -E 'nr_periods|nr_throttled' $(cg $(pid api))/cpu.stat   # 200000 100000 is 2 cores, and nr_throttled stays flat",
      "bpftrace -e 'profile:hz:99 { @[comm] = count(); } interval:s:10 { exit(); }'   # needs BPF: one thread name gets most of the samples"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core)"
  )

  private fun cacheMiss() = Scenario(
    id = "cache_miss",
    title = "Cache misses push the load onto the DB",
    level = 2,
    topics = listOf("layers", "red"),
    params = { random, context -> mapOf("ttl" to random.nextInt(100, 401), "query_ms" to queryMs(context)) },
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
        "The API rate is flat, and the product_detail query rate at the DB climbs from a few per second to near the /products rate of " +
        "$PRODUCTS_PER_SECOND per second. ${missLatency(p)} " +
        "This is rate rising at a lower layer because of the cache, not because of users. The missing index on reviews was always there; " +
        "the cache is what kept it cheap, and the cache is what changed."
    },
    steps = listOf(
      "Problem statement: Postgres CPU is up, the DBA asks who hits the products table, and API traffic is unchanged.",
      "Which query grew in Postgres? product_detail. Did the API rate grow with it? No.",
      "Ask what a /products request passes through before the DB: the cache.",
      "The cache hit ratio fell, so almost every /products misses and runs product_detail, a scan over reviews.",
      "The cause: a config change made the cache TTL tiny. The missing index was always there; the cache kept it cheap."
    ),
    evidence = listOf(
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    cli = listOf(
      "for i in 1 2; do curl -s http://api:8000/metrics | grep '^cache_requests_total'; sleep 10; done   # miss grows almost as fast as all lookups",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select calls, round(total_exec_time::numeric) as total_ms, left(query, 60) as query from pg_stat_statements order by total_exec_time desc limit 5\"   # the reviews scan leads by total time"
    ),
    doc = "RED at many layers > Why Rate differs (cache)"
  )

  private fun appLock() = Scenario(
    id = "app_lock",
    title = "Checkouts wait for each other on one lock",
    level = 2,
    topics = listOf("use", "runtime"),
    params = { random, _ ->
      val utilization = random.nextDouble(MIN_LOCK_UTILIZATION, MAX_LOCK_UTILIZATION)
      mapOf("lock_ms" to (utilization * MILLIS_PER_SECOND / CHECKOUTS_PER_SECOND).roundToInt())
    },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("lock_ms" to p.int("lock_ms")))) },
    marker = { "Deploy api v2.40: inventory cache rewrite" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 is many times higher than before. api CPU is low, pricing and Postgres look normal, and /products is fine. " +
        "The rate did not change. What is /checkout waiting for?"
    },
    hints = listOf(
      "Only /checkout is slow and every downstream is flat. Where does a request spend time when it neither computes nor waits on a downstream?",
      "USE for api's software resources: the pool is fine and admission is fine. Look at the inventory lock panels: threads waiting and wait time.",
      "rate(app_lock_held_seconds_total) is the lock's utilization. By M/M/1, at 90% the mean wait is 9 times the hold time."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api"), signal = setOf("lock_wait"), cause = "app_lock"),
    explain = { p ->
      val hold = p.int("lock_ms").toDouble()
      val rho = CHECKOUTS_PER_SECOND * hold / MILLIS_PER_SECOND
      val wait = rho / (1 - rho) * hold
      val atLock = hold / (1 - rho)
      val waiting = CHECKOUTS_PER_SECOND * wait / MILLIS_PER_SECOND
      "v2.40 refreshes an inventory cache on every checkout under one global lock. Each refresh holds it about ${hold.roundToInt()} ms on average. " +
        "At about $CHECKOUTS_PER_SECOND checkouts/s the lock is busy $CHECKOUTS_PER_SECOND × ${p.fixed(hold / MILLIS_PER_SECOND, 3)} s = " +
        "${(rho * PERCENT).roundToInt()}% of the time: that is its utilization. Arrivals and hold times are both random, so the lock is an M/M/1 queue. " +
        "The mean wait is ρ / (1 − ρ) × hold = ${p.fixed(rho, 2)} / ${p.fixed(1 - rho, 2)} × ${hold.roundToInt()} ms ≈ ${wait.roundToInt()} ms. " +
        "Wait plus hold is hold / (1 − ρ) ≈ ${atLock.roundToInt()} ms. In M/M/1 that time has an exponential spread, so its p99 is about " +
        "${p.fixed(P99_OF_EXPONENTIAL, 1)} times the mean: about ${p.fixed(atLock * P99_OF_EXPONENTIAL / MILLIS_PER_SECOND, 1)} s on top of the normal checkout. " +
        "Little's Law gives the threads waiting: $CHECKOUTS_PER_SECOND/s × ${p.fixed(wait / MILLIS_PER_SECOND, 3)} s ≈ ${p.fixed(waiting, 1)}. " +
        "CPU is low because a waiting thread uses none. Pool, pricing and Postgres are flat because the lock sits outside all of them, and /products never takes it. " +
        "A lock is a software resource: utilization is the share of time it is held, saturation is the threads waiting. " +
        "Fix: refresh the cache off the request path, or once per interval instead of once per checkout."
    },
    steps = listOf(
      "Problem statement: /checkout p99 is many times higher, CPU is low, downstreams look normal, /products is fine, the rate is flat.",
      "RED by route: only /checkout moved. Compare its spans: the DB sessions and the pricing call did not grow. Suspect the app itself.",
      "USE for api at every layer: host and container idle, no throttling, event loop quiet, pool and admission fine. No hardware resource is saturated.",
      "No resource saturated and no downstream slow means a wait inside the app, such as a lock. The deploy marker names the inventory cache rewrite.",
      "The lock panels: threads waiting above 0, wait p99 near a second or more, utilization between 75% and 90%. By M/M/1 the mean wait is ρ / (1 − ρ) hold times.",
      "The cause: one global lock serializes checkouts. They wait for each other, not for a resource. Take the refresh off the request path."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(app_lock_wait_seconds_bucket{job=\"api\"}[1m])))",
      "app_lock_waiting{job=\"api\"}",
      "rate(app_lock_held_seconds_total{job=\"api\"}[1m])",
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    doc = "USE method in detail > Software resources (mutex, lock); Off-CPU time > Waiting on a lock"
  )

  private fun forkDecoy() = Scenario(
    id = "fork_decoy",
    title = "A fork storm on the host, slower product pages",
    level = 3,
    topics = listOf("host", "layers"),
    params = { random, context ->
      mapOf("ttl" to random.nextInt(100, 401), "fork_rps" to random.nextInt(MIN_FORK_RPS, MAX_FORK_RPS + 1), "query_ms" to queryMs(context))
    },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("cache_ttl_ms" to p.int("ttl")), "noisy" to mapOf("fork_rps" to p.int("fork_rps")))) },
    marker = { "Deploy checkout-api v2.34: configuration moved to the new config service" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, ops saw the host's fork rate (node_forks_total) jump to more than 100 per second, far above normal. " +
        "They ask whether that is why /products got slower at the same time: its p50 is many times higher than before, though its p99 did not move. " +
        "top shows nothing unusual."
    },
    hints = listOf(
      "Two things are true at once: the host forks a lot, and api is slower. Does the first explain the second? Check host saturation: load, PSI, api's throttled share.",
      "/products is the slow route, and it never touches the host's process table. Why would p50 jump while p99 stays? What does a /products request pass through before the DB?",
      "The cache hit ratio fell at the same minute and product_detail queries at Postgres climbed. The fork storm is a batch job on noisy and hurts nobody."
    ),
    answer = Answer(nature = setOf("amplified", "arch"), location = setOf("cache", "api"), signal = setOf("cache_hit_ratio", "query_rate_by_name"), cause = "cache_miss"),
    explain = { p ->
      "Two changes happened together. The noisy container starts about ${p.int("fork_rps")} short processes per second, so the host's fork counter climbs by about " +
        "that much. Each process lives a few milliseconds, so top never catches one. The whole storm costs a fraction of a core: load stays under the CPU count, " +
        "PSI stays low and api is not throttled. That lead is real but harmless. The slowness has another source: a config change set the cache TTL to " +
        "${p.int("ttl")} ms, so almost every /products misses and runs the product_detail query, a scan over reviews. The API rate is flat while the query rate at " +
        "Postgres climbs to near the /products rate of $PRODUCTS_PER_SECOND per second. ${missLatency(p)} " +
        "The hit ratio and the query rate by name prove the cache miss. The host's saturation numbers clear the fork storm: busy is not saturated."
    },
    steps = listOf(
      "Problem statement: the host's fork rate jumped to more than 100 per second, and /products got slower at the same time.",
      "Check whether the host lead explains the slowness: load against the CPU count, PSI and api's throttled share are all flat. Busy is not saturated.",
      "Ask which container forks: the noisy batch job. Short processes never show in top, only in node_forks_total.",
      "RED by route: /products is the slow one. Its p50 jumped and its p99 stayed: the typical request now costs what only the slowest did. It never depends on the process table.",
      "Follow the request path instead: cache, then DB.",
      "The cache hit ratio fell and the product_detail query rate at Postgres climbed while the API rate stayed flat.",
      "The cause: a config change made the cache TTL tiny. The fork storm is a second, harmless change. The first thing you find is not always the problem."
    ),
    evidence = listOf(
      "rate(node_forks_total[1m])",
      "rate(noisy_forks_total[1m])",
      "node_load1 / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "sum(rate(cache_requests_total{result=\"hit\"}[1m])) / sum(rate(cache_requests_total[1m]))",
      "sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    doc = "RED at many layers > Why Rate differs (cache); CPU busy with no work > Short-lived processes"
  )

  private fun queryMs(context: ExerciseContext): Int =
    ((context.productDetailSeconds ?: DEFAULT_PRODUCT_DETAIL_SECONDS) * MILLIS_PER_SECOND).roundToInt().coerceAtLeast(1)

  private fun missLatency(p: Params): String =
    "A hit is a Redis read of under 1 ms, and a miss pays the query, about ${p.int("query_ms")} ms. Before, about 1 request in 10 missed, " +
      "so the misses set the p99 and the hits set the p50. Now most requests miss: the /products p50 jumps from under 1 ms to about " +
      "${p.int("query_ms")} ms, while the p99 barely moves, because it was a miss already."

  // The lock's utilization at 15 checkouts/s. Above 0.9 the p99 at the lock passes loadgen's 3 s timeout.
  private const val MIN_LOCK_UTILIZATION = 0.75
  private const val MAX_LOCK_UTILIZATION = 0.9

  // ln(100): the p99 of an exponential distribution, in means.
  private const val P99_OF_EXPONENTIAL = 4.6

  // About 100 times the idle host's fork rate; noisy caps the storm at 200/s.
  private const val MIN_FORK_RPS = 100
  private const val MAX_FORK_RPS = 200
}

package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.AVERAGE_BASKET
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PRODUCTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults inside api: its code, its runtime and its cache. */
internal object AppScenarios {

  val all: List<Scenario> = listOf(
    nPlusOne(),
    singleThreadCpu(),
    cacheMiss()
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
      "pidstat -t -p $(pgrep -f checkout-api) 5 1 | grep Average | sort -k9 -nr | head -4   # one thread takes most of the process CPU, under one core",
      "cat $(cg $(pgrep -f checkout-api))/cpu.max; grep -E 'nr_periods|nr_throttled' $(cg $(pgrep -f checkout-api))/cpu.stat   # 200000 100000 is 2 cores, and nr_throttled stays flat",
      "bpftrace -e 'profile:hz:99 { @[comm] = count(); } interval:s:10 { exit(); }'   # needs BPF: one thread name gets most of the samples"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core)"
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
}

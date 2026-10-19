package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_HOLD_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CONNECTIONS_IN_USE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PRICE_BASE_MS
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import java.util.Locale
import kotlin.math.roundToInt

/** Faults on the api -> Postgres leg: the pool on the client side, and locks inside Postgres. */
internal object DatabaseScenarios {

  val all: List<Scenario> = listOf(
    poolHold(),
    poolShrunk(),
    poolTimeout(),
    dbLock()
  )

  private fun poolHold() = Scenario(
    id = "pool_hold",
    title = "Connection pool exhausted after a deploy",
    level = 2,
    topics = listOf("layers", "use", "little"),
    // Half the runs overload the pool (503s), half sit just under it (slow, almost no errors).
    params = { random, _ ->
      val overload = random.nextBoolean()
      mapOf("recommend_ms" to if (overload) random.nextInt(250, 311) else random.nextInt(125, 151), "overload" to overload)
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
      "Where is the gap? Look at dashboard 02, row 1 (Software resources inside api): pool in use, waiting, acquire wait.",
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
    steps = listOf(
      "Problem statement: /checkout p99 jumps right after the v2.31 deploy. The DB team says Postgres is fine.",
      "RED at the LB: the rate is flat against the baseline, so suspect architecture, not load.",
      "LB p99 and api p99 are about equal, so there is no queue before the app. The problem is in the app or behind it.",
      "Compare the DB session time from api with the Postgres exec time: the session time is up and exec time is flat. The gap is on the app side.",
      "USE for the pool: in use at 4/4, waiting above 0, acquire wait high. The pool is the saturated resource.",
      "Little's Law: checkouts/s times the hold time, against pool_max = 4. It is near or above the limit.",
      "Open one /checkout trace: the pricing /price and /recommend calls sit inside db.session, and Postgres shows connections idle in transaction.",
      "The cause: v2.31 holds the connection across the pricing calls, so the pool is exhausted."
    ),
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "db_pool_in_use{job=\"api\"}, db_pool_waiting{job=\"api\"}, db_pool_max{job=\"api\"}",
      "pg_connections{state=\"idle in transaction\"}"
    ),
    cli = listOf(
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select state, count(*) from pg_stat_activity where datname = 'lab' group by state\"   # idle in transaction shows up",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select pid, now() - xact_start as open_for, left(query, 50) as last_query from pg_stat_activity where state = 'idle in transaction'\"   # transactions stay open while api waits for pricing",
      "curl -s http://api:8000/metrics | grep -E '^db_pool_(in_use|waiting|max)'   # in_use at max, waiting above 0"
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
      "The pool panels on dashboard 02, row 1: in use, waiting, and the max. Did the hold time change, or the limit?",
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
    steps = listOf(
      "Problem statement: /checkout p99 is up, Postgres is nearly idle, and the checkout code did not change.",
      "Compare the DB session time from api with the Postgres exec time: session time up, exec time flat. The gap is on the app side.",
      "USE for the pool: in use at the max and waiting above 0. Check what moved: the hold time or the max.",
      "The max moved. Little's Law: the same checkouts/s times the same 50 ms hold is fine against 4, not against 1.",
      "Compare with v2.31: there the hold time (the numerator) grew. Here the limit (the denominator) shrank.",
      "The cause: a config change lowered pool_max. Utilization only means something against the right limit."
    ),
    evidence = listOf(
      "db_pool_max{job=\"api\"}, db_pool_in_use{job=\"api\"}, db_pool_waiting{job=\"api\"}",
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))"
    ),
    cli = listOf(
      "curl -s http://api:8000/metrics | grep -E '^db_pool_(in_use|waiting|max)'   # max is 1, waiting above 0",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select state, count(*) from pg_stat_activity where datname = 'lab' group by state\"   # almost every backend is idle: Postgres is not the limit"
    ),
    doc = "USE at many layers > Common examples (pool full while the DB idles); USE at many layers > How to do it (write the limit next to the metric)"
  )

  private fun poolTimeout() = Scenario(
    id = "pool_timeout",
    title = "Pool acquire timeout set too short",
    level = 2,
    topics = listOf("use", "db"),
    params = { random, _ -> mapOf("pool_max" to 2, "timeout_ms" to random.nextInt(15, 26)) },
    faults = { p ->
      FaultSpec(services = mapOf("api" to mapOf("pool_max" to p.int("pool_max"), "pool_acquire_timeout_s" to p.int("timeout_ms") / MILLIS_PER_SECOND)))
    },
    marker = { "Config change: checkout-api connection pool settings tuned by the platform team" },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, a small share of /checkout requests fail with 503, in short bursts. " +
        "The requests that succeed are about as fast as before, and Postgres is idle."
    },
    hints = listOf(
      "Which layer creates the 503s? Compare the LB, api and the downstreams.",
      "USE for the pool, errors first: look at Pool timeouts/s next to in use and max.",
      "The pool is not full on average. How long does a request wait for a connection before it gives up?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("api_db", "api"), signal = setOf("pool"), cause = "pool_timeout_short", partial = setOf("pool_exhausted")),
    steps = listOf(
      "Problem statement: some 503s in bursts on /checkout, normal latency for the rest, Postgres idle, a config change on the pool.",
      "RED at the LB and api: api itself returns the 503s, so the LB is not the source.",
      "Postgres exec time is flat and pricing is healthy, so look inside api at its software resources.",
      "USE for the pool, errors first: Pool timeouts/s is above 0 while in use averages well under the max.",
      "Wait for a connection p99 sits flat at about 20 ms: nobody waits longer, they give up. That ceiling is the acquire timeout, far below the 50 ms a checkout holds a connection.",
      "The cause: the acquire timeout is too short, so a short burst that fills the pool turns into errors instead of a small wait."
    ),
    explain = { p ->
      val busy = BASE_CONNECTIONS_IN_USE / p.int("pool_max") * PERCENT
      "The platform team set the pool to ${p.int("pool_max")} connections and the acquire timeout to ${p.int("timeout_ms")} ms. " +
        "A checkout holds a connection about $BASE_CHECKOUT_HOLD_MS ms, so about ${p.fixed(BASE_CONNECTIONS_IN_USE, 1)} connections are in use on average: " +
        "${busy.roundToInt()}% of the pool. That is fine on average, but arrivals come in bursts. Now and then both connections are busy, " +
        "and a request that waits longer than ${p.int("timeout_ms")} ms gives up with a 503. Utilization looks harmless; " +
        "the errors column of USE (pool timeouts) is what shows the problem. Compare with the pool shrink exercise: there requests wait, " +
        "here they give up. Fix: an acquire timeout well above the hold time, and a pool with headroom."
    },
    evidence = listOf(
      "rate(db_pool_timeouts_total{job=\"api\"}[1m])",
      "avg_over_time(db_pool_in_use{job=\"api\"}[1m])",
      "db_pool_max{job=\"api\"}",
      "histogram_quantile(0.99, sum by (le) (rate(db_pool_acquire_seconds_bucket{job=\"api\"}[1m])))",
      "sum by (route, status) (rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[1m]))"
    ),
    doc = "USE method in detail > Software resources (connection pool errors); USE method in detail > Reading the numbers (errors above 0)"
  )

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
    steps = listOf(
      "Problem statement: /checkout is slow with 503s, and the DB team says Postgres exec time went up.",
      "Find which query's exec time rose inside Postgres: update_inventory.",
      "Exec time is up, so look at what Postgres is doing: pg_lock_waiters is above 0 and backends wait on a lock.",
      "Check the code path: every checkout updates the same inventory row, HOT-1.",
      "The pool drains too, because connections are held while they wait for the lock. That is a consequence, not the cause.",
      "The cause: lock contention on one hot row. Unlike the pool exercise, Postgres itself reports the higher exec time."
    ),
    evidence = listOf(
      "sum by (query) (rate(pg_query_exec_seconds_total[1m])) / sum by (query) (rate(pg_query_calls_total[1m]))",
      "pg_lock_waiters",
      "pg_connections"
    ),
    cli = listOf(
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select pid, wait_event_type, wait_event, pg_blocking_pids(pid) as blocked_by, left(query, 40) as query from pg_stat_activity where wait_event_type = 'Lock'\"   # backends wait on transactionid or tuple, each blocked by another one",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select locktype, mode, count(*) from pg_locks where not granted group by 1, 2\"   # lock requests waiting to be granted",
      "PGPASSWORD=lab psql -h postgres -U lab lab -c \"select calls, round(mean_exec_time::numeric, 1) as mean_ms, left(query, 60) as query from pg_stat_statements order by mean_exec_time desc limit 5\"   # the inventory update has the highest mean time"
    ),
    doc = "USE method in detail > Software resources (mutex, lock); End-to-end investigation example (compare with it)"
  )
}

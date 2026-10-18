package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.CHECKOUTS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.DEFAULT_CPU_COUNT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PRICE_BASE_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.THOUSANDTHS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.round2
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults in pricing, the downstream service api calls, and the way api reads them. */
internal object DownstreamScenarios {

  val all: List<Scenario> = listOf(
    downstreamSlow(),
    downstreamErrors(),
    apiTimeout(),
    pricingCpu(),
    tailLatency(),
    twoFaults()
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
    steps = listOf(
      "Problem statement: /checkout p99 is several times higher and /products is normal.",
      "RED by route: /checkout duration is up and the rate is flat.",
      "Inside /checkout, find the downstream that rose: compare api's calls to pricing with the DB and Kafka.",
      "Compare api's view of pricing with pricing's own server-side p99: the time rose on pricing's side, so it is not the network or api's pool.",
      "pricing's CPU and event loop are quiet, so it waits on something; it does not compute.",
      "The cause: pricing is slow. The next step is latency analysis inside pricing."
    ),
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
    steps = listOf(
      "Problem statement: some checkouts fail with 502, mobile is not retrying, and /products is fine.",
      "Errors at the client, the LB and api are the same count. Attempts per request stay at 1, so nothing is amplified.",
      "api's /checkout errors come from one downstream call: read http_client_requests_total by outcome.",
      "pricing's own RED shows 503 on /price.",
      "The cause: pricing returns errors, and they pass through the layers unchanged. In the retry storm the client multiplies them instead."
    ),
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
    steps = listOf(
      "Problem statement: about half of the checkouts fail with 502, and pricing says every request returns 200, just slower.",
      "Errors at the client, the LB and api are the same. Find which downstream call fails inside api.",
      "Read http_client_requests_total by outcome: it is a timeout on api's side, not a 5xx from pricing.",
      "Compare api's timeout towards pricing with pricing's p99 on /price: the p99 is above the timeout.",
      "The cause: api gives up before pricing answers. On this hop api is the client. Set the timeout from pricing's real p99."
    ),
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
    steps = listOf(
      "Problem statement: /checkout p99 is high, /products is normal, and pricing uses less than 1 of its 2 cores.",
      "Compare api's calls to pricing with pricing's own p99: pricing is slow on its own side.",
      "USE for pricing: process CPU, event loop lag, throttling.",
      "Process CPU sits near one core, event loop lag climbs, and there is no throttling: one thread is saturated.",
      "The cause: CPU work on pricing's single event loop. The slow-downstream exercise stops at pricing is slow; USE names the resource."
    ),
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"pricing\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"pricing\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))"
    ),
    cli = listOf(
      "pidstat -t -p $(pgrep -f pricing-api) 5 1 | grep Average | sort -k9 -nr | head -4   # one pricing thread takes most of the process CPU, under one core",
      "cat $(cg $(pgrep -f pricing-api))/cpu.max; grep -E 'nr_periods|nr_throttled' $(cg $(pgrep -f pricing-api))/cpu.stat   # 2 cores, and nr_throttled stays flat",
      "pidstat -t -p $(pgrep -f checkout-api) 5 1 | grep Average | sort -k9 -nr | head -4   # api itself is nearly idle: it waits for pricing"
    ),
    doc = "USE method in detail > Pitfalls (total CPU hides one core); USE at many layers > A service as a resource"
  )

  private fun tailLatency() = Scenario(
    id = "tail_latency",
    title = "Only the tail is slow",
    level = 2,
    topics = listOf("red", "layers"),
    params = { random, _ -> mapOf("frac" to random.nextInt(15, 26) / THOUSANDTHS, "extra" to random.nextInt(800, 1201)) },
    faults = { p -> FaultSpec(services = mapOf("pricing" to mapOf("extra_latency_ms" to p.int("extra"), "slow_fraction" to p.double("frac")))) },
    marker = { "Deploy pricing v3.7: rules cache refreshed in the request path" },
    decoy = 0.5,
    ticket = { p -> "At ${p.startedAt}, the /checkout p99 alert fires. The mean latency panel is flat, and most users say the app is fine." },
    hints = listOf(
      "Read p50 and p99 of /checkout side by side. Which one moved?",
      "Which downstream call has the same tail? Compare api's calls to pricing with the DB.",
      "Compare api's view of pricing with pricing's own server-side p99 and p50."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), signal = setOf("api_vs_downstream"), cause = "downstream_slow"),
    steps = listOf(
      "Problem statement: a p99 alert on /checkout, the mean is flat, most users are fine.",
      "RED with the distribution: p99 jumped by about a second, p50 did not move. A small share of requests is very slow.",
      "Find the span with the same tail: api's calls to pricing have it; the DB spans do not.",
      "pricing's own p99 rose the same amount and its p50 is flat, so the slow part is inside pricing.",
      "The cause: pricing is slow for about 2% of calls. The mean hides it; the percentiles show it."
    ),
    explain = { p ->
      val meanShift = p.double("frac") * p.int("extra")
      "About ${p.fixed(p.double("frac") * PERCENT, 1)}% of pricing's /price calls take about ${p.int("extra")} ms longer. That is more than 1% of the requests, " +
        "so p99 of /checkout moves by about that much while p50 stays where it was. The mean moves by only about ${p.fixed(meanShift, 0)} ms. " +
        "Percentiles show what the mean hides. pricing's own server-side p99 rose by the same amount, so the time is spent inside pricing. " +
        "A request that called pricing many times in parallel would hit this tail most of the time: that is tail amplification."
    },
    evidence = listOf(
      "histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))",
      "histogram_quantile(0.5, sum by (le) (rate(http_server_duration_seconds_bucket{job=\"api\",route=\"/checkout\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))"
    ),
    doc = "RED method in detail > Duration; RED at many layers > Tail amplification"
  )

  private fun twoFaults() = Scenario(
    id = "two_faults",
    title = "Two changes at once, one of them harmless",
    level = 3,
    topics = listOf("layers", "host"),
    params = { random, context ->
      mapOf("burners" to ((context.cpuCount ?: DEFAULT_CPU_COUNT) / 2).coerceAtLeast(1), "extra" to random.nextInt(250, 351))
    },
    faults = { p ->
      FaultSpec(services = mapOf("noisy" to mapOf("burners" to p.int("burners")), "pricing" to mapOf("price_base_ms" to PRICE_BASE_MS + p.int("extra"))))
    },
    marker = { "Batch job: nightly report started on the shared host" },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, /checkout p99 is several times higher and /products is normal. " +
        "Someone already found the host CPU at about half and wants to stop the batch job."
    },
    hints = listOf(
      "Host CPU is busy, but is anyone waiting for a CPU? Look at load against the CPU count, PSI and throttling.",
      "Only /checkout is slow. Which downstream does only /checkout call?",
      "Compare api's calls to pricing with pricing's own p99."
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("pricing"), signal = setOf("api_vs_downstream"), cause = "downstream_slow"),
    steps = listOf(
      "Problem statement: /checkout slow, /products normal, a batch job started, and a teammate already blames it.",
      "USE for the host, saturation before utilization: CPU is about half busy, but load is under the CPU count, PSI is low and nothing is throttled.",
      "Busy is not saturated, and a host problem would slow /products too. Keep going instead of stopping at the first finding.",
      "RED by route: only /checkout is slow. Compare its downstream spans: the pricing call rose by the same amount.",
      "pricing's own p99 and p50 both rose, so pricing got slower on every call.",
      "The cause: pricing is slow. The batch job is real but harmless; stopping it would change nothing for users."
    ),
    explain = { p ->
      "Two things changed. A batch job keeps about half of the host's CPU busy (${p.int("burners")} threads), and pricing's /price got about " +
        "${p.int("extra")} ms slower on every call. Only the second one hurts users. The host is busy but not saturated: load stays under the CPU count, " +
        "CPU PSI is low and no service is throttled. /checkout rose by exactly the pricing span and /products, which never calls pricing, did not move. " +
        "The first thing you find may be a problem, but not the problem. Stopping the batch job would have been the streetlight anti-method."
    },
    evidence = listOf(
      "node_load1 / scalar(count(node_cpu_seconds_total{mode=\"idle\"}))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_client_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"pricing\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    doc = "USE method in detail > Order of checks (the first thing you find); Combining with other methods > Anti-methods"
  )
}

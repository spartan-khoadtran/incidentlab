package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults that the metrics and the traces can locate but only a CPU profile can name. */
internal object ProfilingScenarios {

  val all: List<Scenario> = listOf(
    hotCodePath()
  )

  private fun hotCodePath() = Scenario(
    id = "hot_code_path",
    title = "A hot code path on every request",
    level = 3,
    topics = listOf("profiling", "use", "runtime"),
    params = { random, _ -> mapOf("render" to random.nextInt(MIN_RENDER_MS, MAX_RENDER_MS + 1)) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("render_ms" to p.int("render")))) },
    marker = { "Deploy checkout-api v2.42: new receipt renderer" },
    decoy = 0.3,
    ticket = { p ->
      "At ${p.startedAt}, p99 went up on every route, /products and /checkout alike, and the CPU alarm on api fired. " +
        "Traffic looks normal. I checked event loop lag and it is flat, so it is not the event loop again. Which code is eating the CPU?"
    },
    hints = listOf(
      "Rate flat, p99 up everywhere. Is the extra time inside api, or in a call api makes? Compare api's p99 with its downstream calls.",
      "USE for api: process CPU against the 2-core limit, and event loop lag. Is one thread busy, or many?",
      "Open Explore > Pyroscope, service_name api, profile type process_cpu. Which function owns most of the samples, against a window before the start?"
    ),
    answer = Answer(
      nature = setOf("arch"),
      location = setOf("api"),
      signal = setOf("cpu_profile"),
      cause = "hot_code_path",
      partial = setOf("single_thread_cpu")
    ),
    explain = { p ->
      val renderMs = p.int("render")
      val cores = BASE_RPS * renderMs / MILLIS_PER_SECOND
      val busy = (cores / CARRIERS * PERCENT).roundToInt()
      "v2.42 renders every response with Jackson again and again: about $renderMs ms of CPU per request, on /products and /checkout alike. " +
        "At about $BASE_RPS requests/s that is $BASE_RPS × ${p.fixed(renderMs / MILLIS_PER_SECOND, 3)} s = ${p.fixed(cores, 2)} cores of render work, " +
        "on top of a small healthy baseline. The work runs on each request's own virtual thread. Virtual threads share $CARRIERS carrier threads, " +
        "one per core of the 2-core limit, so rendering alone keeps the carriers about $busy% busy. Requests wait for a free carrier, " +
        "so p99 rises on every route while the rate stays flat. The event loop is a separate thread with little to do, so its lag stays flat. " +
        "That is the difference from single_thread_cpu, where one thread sits at 1 core and the lag climbs. Downstream calls stay flat too. " +
        "A trace shows a long api span with no child span to blame, because nobody put a span around the renderer. " +
        "The CPU profile names the code: ReceiptRenderer.render and the Jackson frames under it own most of the samples. " +
        "A few throttled periods can show up when both carriers and other threads run at once. A bigger limit buys headroom, " +
        "but every request still pays the render. Fix: render once and keep the result, or roll back v2.42."
    },
    steps = listOf(
      "Problem statement: p99 is up on every route, api's CPU alarm fired, the rate is normal, and a deploy landed just before.",
      "RED: the rate is flat, and the LB and api p99 rise together. The DB session, pricing and cache calls from api are flat, so the time is inside api.",
      "USE for api: process CPU climbs to well over 1 core of the 2-core limit, and event loop lag stays flat. Many threads share the work, not one.",
      "Open a slow trace: api's own span is long and no child span explains the gap. A trace only sees code that has a span.",
      "Open the CPU profile: Explore > Pyroscope, service_name api, profile type process_cpu, the last 15 minutes. ReceiptRenderer.render and Jackson under it own most of the samples.",
      "Confirm with a window before the start (the diff view in Pyroscope, or two time ranges): the renderer was not there before v2.42.",
      "The cause: a hot code path on every request. Do it once: render one time and keep the result. Right now: roll back v2.42."
    ),
    evidence = listOf(
      "rate(process_cpu_seconds_total{job=\"api\"}[1m])",
      "histogram_quantile(0.99, sum by (le) (rate(event_loop_lag_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_server_duration_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, op) (rate(db_client_session_seconds_bucket{job=\"api\"}[1m])))",
      "histogram_quantile(0.99, sum by (le, route) (rate(http_client_duration_seconds_bucket{job=\"api\"}[1m])))"
    ),
    cli = listOf(
      "top -H -b -n 1 -p $(pgrep -f checkout-api) | head -20   # two threads busy at once, the virtual-thread carriers: CPU work on every request, not one event loop thread"
    ),
    doc = "CPU profiling > Reading a flame graph; Profiles in this lab > A good first query"
  )

  private const val MIN_RENDER_MS = 24
  private const val MAX_RENDER_MS = 30
  private const val CARRIERS = 2
}

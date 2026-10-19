package com.c0x12c.incidentlab.labctl.scenario.catalog

import com.c0x12c.incidentlab.labctl.scenario.Answer
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.SECONDS_PER_HOUR
import com.c0x12c.incidentlab.labctl.scenario.Baseline.round2
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Scenario
import kotlin.math.roundToInt

/** Faults at the edge: the load balancer, and the queue before the app accepts a request. */
internal object EdgeScenarios {

  val all: List<Scenario> = listOf(
    queueBeforeApp(),
    lbNoHealthy(),
    lbFlapping()
  )

  private fun queueBeforeApp() = Scenario(
    id = "queue_before_app",
    title = "Requests queue before the app accepts them",
    level = 2,
    topics = listOf("layers", "red"),
    params = { _, _ -> mapOf("limit" to 2) },
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
      "The api's concurrency limit was set to ${p.int("limit")}: only ${p.int("limit")} requests at a time enter the handler, the rest wait ahead of them. " +
        "The duration api measures (APM) starts when the handler begins, so it stays flat. The LB measures from the moment it receives the request, " +
        "so it sees the wait too. The gap between LB latency and api latency is the queueing time: saturation showing up as duration."
    },
    steps = listOf(
      "Problem statement: users say the app is slow, but api says its APM p99 is normal, so it must be the network.",
      "Put the LB (Envoy) p99 and the api p99 on one chart.",
      "The two lines split: the LB sees much more time than api does.",
      "The gap is time the request waits before the handler starts. Check the Admission wait p99 panel.",
      "The cause: the concurrency limit is too low, so requests queue before the app accepts them. APM cannot see that wait."
    ),
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
      "api's /health answers 503 with probability ${p.percent("prob")}. Envoy treats a 503 from the health check as an immediate failure and marks the backend unhealthy at once; " +
        "with panic mode off, it answers 503 'no healthy upstream' itself during those windows. Requests never reach api, so APM sees a lower rate, " +
        "not errors. This is 'errors the LB produces itself' from RED at many layers."
    },
    steps = listOf(
      "Problem statement: users get scattered 503s, and api insists APM shows no errors.",
      "Compare 5xx at the client, the LB and api: the LB returns 5xx that api never records.",
      "Check Envoy's healthy backends: the count drops to 0 at times, because api's /health fails.",
      "With panic mode off, Envoy answers 503 itself. The rate reaching api drops instead of api's errors rising.",
      "The cause: the LB produces the 503s because no backend is healthy."
    ),
    evidence = listOf(
      "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"5\",envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[1m])) or vector(0)",
      "sum(rate(envoy_cluster_upstream_cx_none_healthy{envoy_cluster_name=\"api\"}[1m]))",
      "envoy_cluster_membership_healthy{envoy_cluster_name=\"api\"}",
      "sum(rate(http_server_requests_total{job=\"api\"}[1m]))"
    ),
    cli = listOf(
      "for i in 1 2 3 4 5; do curl -s http://envoy:9901/clusters | grep health_flags; sleep 2; done   # the api host often shows /failed_active_hc",
      "for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{http_code}\\n' http://api:8000/health; done   # api's own health check fails about half the time"
    ),
    doc = "RED at many layers > Why Errors differ"
  )

  private fun lbFlapping() = Scenario(
    id = "lb_flapping",
    title = "The LB drops a few requests now and then",
    level = 2,
    topics = listOf("layers", "red"),
    params = { random, _ -> mapOf("prob" to round2(random.nextDouble(0.03, 0.05))) },
    faults = { p -> FaultSpec(services = mapOf("api" to mapOf("health_fail_prob" to p.double("prob")))) },
    decoy = 0.5,
    ticket = { p ->
      "At ${p.startedAt}, monitoring shows a small share of 503s that come and go every few seconds. " +
        "The api team sees no errors in APM and asks whether it is worth a look."
    },
    hints = listOf(
      "Compare the 5xx the LB returns with the 5xx api records itself.",
      "Look at Envoy's healthy backends over time. Does the count drop?",
      "The ratio is small. How many failed requests is that per hour?"
    ),
    answer = Answer(nature = setOf("arch"), location = setOf("lb", "api"), signal = setOf("lb_vs_api_errors"), cause = "lb_no_healthy"),
    steps = listOf(
      "Problem statement: a few % of 503s in short bursts, APM shows no errors.",
      "Compare 5xx at the LB with 5xx at api: only the LB has them.",
      "Envoy's healthy backends drop to 0 for about 2 s at a time, because api's /health fails now and then.",
      "With panic mode off, Envoy answers 503 itself while no backend is healthy, so api never sees those requests.",
      "Ratio and count: a few % is small, but at 50 rps it is thousands of failed requests per hour. Errors above 0 deserve a look.",
      "The cause: the LB produces the 503s because its only backend flaps between healthy and unhealthy."
    ),
    explain = { p ->
      val failedPerHour = (p.double("prob") * BASE_RPS * SECONDS_PER_HOUR).roundToInt()
      "api's /health fails with probability ${p.percent("prob")}. Envoy checks every 2 s and treats a 503 from the health check as an immediate failure. " +
        "Each time, Envoy answers 503 itself until the next good check 2 s later, so users see short bursts of 503 that api never records. " +
        "The ratio is small, but at about $BASE_RPS requests/s it adds up to about $failedPerHour failed requests per hour. " +
        "Same mechanism as the LB 503 exercise, with a much smaller and noisier " +
        "signal: watch both the ratio and the count."
    },
    evidence = listOf(
      "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"5\",envoy_http_conn_manager_prefix=\"ingress\"}[1m]))",
      "sum(rate(http_server_requests_total{job=\"api\",status=~\"5..\"}[1m])) or vector(0)",
      "envoy_cluster_membership_healthy{envoy_cluster_name=\"api\"}",
      "increase(envoy_http_downstream_rq_xx{envoy_response_code_class=\"5\",envoy_http_conn_manager_prefix=\"ingress\"}[1h])"
    ),
    doc = "RED at many layers > Why Errors differ; RED method in detail > Errors (ratio and count)"
  )
}

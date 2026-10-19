package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/capacity.md: one situation, four readings, one of them right. */
internal object CapacityConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Capacity from a ramp test
    ChoiceQuestion(
      text = "The Utilization Law says U = X × S. What happens to a resource's utilization when the throughput doubles and the service time stays the same?",
      answer = "It doubles, until it reaches 100%. Past that the extra work queues or fails",
      others = listOf(
        "It stays the same, because utilization only depends on the service time",
        "It goes up by the square of the change, so it quadruples",
        "It halves, because the work is spread over more requests"
      ),
      explanation = "U grows in line with X for a fixed S. At 100% the resource cannot take more, so X stops at c / S for c parallel units.",
      doc = "Capacity from a ramp test > Utilization Law"
    ),
    ChoiceQuestion(
      text = "Two resources sit on the request path. The pool can serve at most 267 requests/s, the consumer at most 377. Which one is the bottleneck?",
      answer = "The pool: the resource with the lowest maximum rate limits the whole path",
      others = listOf(
        "The consumer: the resource with the highest maximum rate does the most work",
        "Neither: the two limits add up to 644 requests/s",
        "Both equally: a path has no single bottleneck"
      ),
      explanation = "Every request needs both, so the path cannot go faster than its slowest resource. X max of the path is the lowest X max on it.",
      doc = "Capacity from a ramp test > Utilization Law"
    ),
    ChoiceQuestion(
      text = "In a ramp test, p99 bends up sharply at 150 requests/s (above 3 times the first step), but achieved throughput keeps growing until 200 requests/s. Which number is the capacity to plan with?",
      answer = "150 requests/s, the knee: past it a small step in load adds a lot of latency",
      others = listOf(
        "200 requests/s, the maximum: the service still answers there",
        "175 requests/s, halfway between the two",
        "Neither: capacity can only be read from CPU utilization"
      ),
      explanation = "At the maximum there is no room for a burst. Queueing delay grows before the resource is full, so latency bends first.",
      doc = "Capacity from a ramp test > Finding the knee"
    ),
    ChoiceQuestion(
      text = "Why leave the first seconds of each ramp step out of its measuring window?",
      answer = "They still carry the step before: the rate window and the queue need time to settle",
      others = listOf(
        "Prometheus does not scrape during the first seconds of a step",
        "The load generator sends nothing at the start of a step",
        "The first seconds always have errors that should be ignored"
      ),
      explanation = "A rate over a window that starts before the step mixes two loads. The queue from the step before also takes time to drain or build.",
      doc = "Capacity from a ramp test > Finding the knee"
    ),
    ChoiceQuestion(
      text = "The peak is 150 requests/s and the knee is 200. One person says 25% headroom, another says 33%. Who is right?",
      answer = "Both: 25% is the share of capacity still free, 33% is how much traffic can still grow",
      others = listOf(
        "Only 25%: headroom is always measured against the peak",
        "Only 33%: the free share is not a real measure",
        "Neither: headroom is the knee minus the peak, 50 requests/s, and has no %"
      ),
      explanation = "1 − 150 / 200 = 25%, and 200 / 150 − 1 = 33%. Always say which one you mean.",
      doc = "Capacity from a ramp test > Headroom and the next saturation"
    ),
    ChoiceQuestion(
      text = "The pool was the bottleneck at 267 requests/s. The team makes the pool six times larger. What should they check before they call the capacity six times higher?",
      answer = "The maximum rate of every other resource on the path: the next lowest one is the new limit",
      others = listOf(
        "Nothing: the capacity grows with the size of the bottleneck",
        "Only the pool's utilization, which is now lower",
        "Only the CPU, because a bigger pool always moves the limit to CPU"
      ),
      explanation = "Fixing a bottleneck moves it. In this lab the Kafka consumer is next at about 377 requests/s, and it shows as lag, not as api latency.",
      doc = "Capacity from a ramp test > Headroom and the next saturation"
    ),
    ChoiceQuestion(
      text = "Why does `make ramp` refuse to start after `make load X=1.5`?",
      answer = "Its steps are set from the normal 50 requests/s. Another background load would shift every step",
      others = listOf(
        "The ramp and the load command use the same Kafka topic",
        "Prometheus cannot store two load settings at once",
        "The ramp needs the api CPU limit that the load command changes"
      ),
      explanation = "loadgen multiplies the background load by the surge the ramp sets. At 1.5 the steps would run from 75 to 450 requests/s, not the planned rates.",
      doc = "Capacity from a ramp test > The ramp in this lab"
    ),
    ChoiceQuestion(
      text = "In `make ramp`, p99 stays near step 1's, errors stay at 0, and achieved follows offered up to the last step. What is the right resource answer?",
      answer = "Nothing within the range: no knee was measured",
      others = listOf(
        "The pool, because it is the usual bottleneck in this lab",
        "The CPU, because the load went up six times",
        "The Kafka consumer, because it is async and never shows"
      ),
      explanation = "With no measured knee, nothing limited the lab inside the ramp. When the pool is not the hidden setup, the ramp opens it to 24, so it cannot fill at 300 requests/s.",
      doc = "Capacity from a ramp test > The ramp in this lab"
    ),

    // ---------------------------------------------------------------- Closed-loop clients in this lab
    ChoiceQuestion(
      text = "What is a closed-loop client?",
      answer = "A client that sends one request, waits for the answer, then sends the next",
      others = listOf(
        "A client that sends requests at a fixed rate whatever the service does",
        "A client that closes its connection after every request",
        "A client that only calls services inside the same network"
      ),
      explanation = "Its rate depends on how fast the service answers: N = X × R with no think time. An open client keeps its rate.",
      doc = "Closed-loop clients in this lab > What make bench does"
    ),
    ChoiceQuestion(
      text = "During `make bench` with 20 clients, the rate climbs from 50 to about 267 requests/s and api in-flight sits near 20. What is happening?",
      answer = "The clients push the rate up until the pool is full. Then the rate stays at the pool's maximum and N = X × R sets the time",
      others = listOf(
        "The open load and the closed clients both run, so the rates add up",
        "The pool grew to 20 connections for the bench",
        "Each client sends 13 requests at once"
      ),
      explanation = "With no think time, 20 clients keep 20 requests in the system. The pool of 4 caps the rate at about 267 requests/s, so R = 20 / 267 = 75 ms.",
      doc = "Closed-loop clients in this lab > What make bench does"
    ),
    ChoiceQuestion(
      text = "A closed-loop benchmark reports a fine p99 at 267 requests/s. An open load at the same rate times out. What hides the problem in the benchmark?",
      answer = "The closed clients wait instead of sending: the queue never passes N, and the requests users would have sent while the service was slow are never measured",
      others = listOf(
        "The benchmark used a shorter histogram bucket range",
        "The open load sends larger requests",
        "The benchmark ran on a faster machine"
      ),
      explanation = "Real users keep arriving, so their queue has no cap and each one meets every stall. A closed client keeps at most N requests inside and records one slow sample per stall. That is coordinated omission.",
      doc = "Closed-loop clients in this lab > Why the two p99 values differ"
    ),
    ChoiceQuestion(
      text = "Why is the lab's loadgen open by default?",
      answer = "It keeps the arrival rate when the service slows down, so the queue and its latency show the way users feel them",
      others = listOf(
        "It uses less CPU than closed-loop clients",
        "Prometheus can only scrape an open load generator",
        "Open load never makes timeouts, so the dashboards stay clean"
      ),
      explanation = "With open arrivals the queue has no cap, as with real users. A closed loop caps it at N requests and hides the overload.",
      doc = "Closed-loop clients in this lab > Why the two p99 values differ"
    )
  )
}

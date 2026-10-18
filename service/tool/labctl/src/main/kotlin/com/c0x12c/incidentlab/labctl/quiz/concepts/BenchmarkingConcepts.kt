package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/benchmarking.md: one situation, four readings, one of them right. */
internal object BenchmarkingConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Benchmarking mistakes
    ChoiceQuestion(
      text = "A teammate reports: I ran the load tool once and got 800 rps. What is missing before the number means anything?",
      answer = "The plan and the record: the question, the setup, repeat runs with their spread, the success share, the server's USE",
      others = listOf(
        "Nothing: 800 rps is a result",
        "A second tool to confirm the number",
        "A longer run, since 800 is probably too low"
      ),
      explanation = "A casual benchmark runs once with no plan, no repeat and no record of the setup. It cannot be reproduced, so it cannot be compared.",
      doc = "Benchmarking mistakes > Casual benchmarking"
    ),
    ChoiceQuestion(
      text = "A benchmark of GET /products/1 on the lab shows 3,000 rps with Postgres idle. What did it measure?",
      answer = "Redis: the same product id is a warm cache hit every time, so Postgres never ran",
      others = listOf(
        "The DB's read capacity",
        "Envoy's limit",
        "The checkout path"
      ),
      explanation = "A warm cache only measures the cache. Spread the ids or clear the cache to measure a miss. The server's pool and DB metrics staying flat is the tell.",
      doc = "Benchmarking mistakes > Benchmarking the wrong thing"
    ),
    ChoiceQuestion(
      text = "During a load test the client machine sits at 100% CPU and the server at 30%. Whose limit is the reported rate?",
      answer = "The client's: it measured itself, and the server has room",
      others = listOf(
        "The server's: the rate is what arrived",
        "The network's",
        "Nobody's: 100% on the client is normal"
      ),
      explanation = "A client that is itself the bottleneck caps the rate before the server does. Watch the client's own CPU during every run.",
      doc = "Benchmarking mistakes > Benchmarking the wrong thing"
    ),
    ChoiceQuestion(
      text = "Run A: 1,000 rps, 100% success, mean 100 ms. Run B: 1,000 rps, 70% success, mean 70 ms. Which run served users better?",
      answer = "A: B serves 300 fewer successful requests per second, and its errors are fast, which pulls the mean down",
      others = listOf(
        "B: lower latency at the same rate",
        "They are equal: same rate",
        "B: 70 ms is under the SLO"
      ),
      explanation = "Errors are fast. Report the success share next to the latency and compute achieved rate × success share.",
      doc = "Benchmarking mistakes > Ignoring errors"
    ),
    ChoiceQuestion(
      text = "Three runs of A give 780, 805 and 820 rps. Three runs of B give 800, 815 and 840 rps. Is B faster?",
      answer = "Not proven: the two spreads overlap",
      others = listOf(
        "Yes: the mean of B is higher",
        "Yes: the best run of B beats the best run of A",
        "No: A has the smaller spread"
      ),
      explanation = "Compare A against B only when the spreads do not overlap. One run each proves even less.",
      doc = "Benchmarking mistakes > Ignoring variance"
    ),
    ChoiceQuestion(
      text = "Why throw away the first minute of a benchmark against a JVM service?",
      answer = "The JVM compiles hot code while it runs, caches fill and connections open, so the first minute is slower",
      others = listOf(
        "The load tool needs a minute to reach its rate",
        "Prometheus only scrapes once a minute",
        "The first minute is always the fastest, which skews the mean"
      ),
      explanation = "Warm up first, then run several times and report the spread. One run is an anecdote.",
      doc = "Benchmarking mistakes > Ignoring variance"
    ),
    ChoiceQuestion(
      text = "You raise the pool from 4 to 8 and cut the pricing timeout from 2 s to 500 ms, then run once: p99 goes from 400 to 175 ms. What do you know about the timeout change?",
      answer = "Nothing: with two changes in one run, either one could be the cause",
      others = listOf(
        "It helped a lot, since both were needed",
        "It did nothing, since timeouts never affect p99",
        "It caused the improvement"
      ),
      explanation = "Four runs (baseline, each change alone, both) separate the two. In the example, the pool alone gives 180 ms and the timeout alone 390 ms.",
      doc = "Benchmarking mistakes > Changing many things at once"
    ),
    ChoiceQuestion(
      text = "What makes a benchmark active rather than passive?",
      answer = "Watching RED on the client and USE on the server during the run, to find what limits the result",
      others = listOf(
        "Running the tool from more than one machine",
        "Using an open load model",
        "Ramping the load up instead of holding it"
      ),
      explanation = "A passive run reads one number at the end. An active run can say: 800 rps, capped by the pool at 4 of 4 with 6 waiting.",
      doc = "Benchmarking mistakes > Active against passive benchmarking"
    ),

    // ---------------------------------------------------------------- Open and closed load models
    ChoiceQuestion(
      text = "A load tool with 20 virtual users. Each sends the next request after the reply, with no think time. The server slows from 50 ms to 450 ms. What happens to the rate?",
      answer = "It falls by itself, from 400 to about 44 requests/s, and the server never looks overloaded",
      others = listOf(
        "It stays at 400 requests/s and a queue builds",
        "It rises, since slow replies make users retry",
        "It falls to zero"
      ),
      explanation = "Closed loop: X = N / (R + Z). 20 / 0.05 = 400, then 20 / 0.45 = 44. The queue never grows past N.",
      doc = "Open and closed load models > Closed loop"
    ),
    ChoiceQuestion(
      text = "In an open load model, what happens when the server runs above its capacity?",
      answer = "Arrivals keep coming at their rate, so the queue and the latency grow until requests time out",
      others = listOf(
        "The arrival rate drops to match the server",
        "Nothing: open loop means no queue",
        "The clients stop until the server catches up"
      ),
      explanation = "Arrivals do not depend on the server, like real users. That is the loud shape of a real overload: rising p99, timeouts, in-flight climbing.",
      doc = "Open and closed load models > Open loop"
    ),
    ChoiceQuestion(
      text = "Which load model does the lab's loadgen use, and why does a saturated pool show as a rising p99 instead of a lower rate?",
      answer = "Open: Poisson arrivals per tenant that do not slow down when api does",
      others = listOf(
        "Closed: a fixed set of virtual users per tenant",
        "Closed: one request at a time per tenant",
        "Open, but the rate falls because of retries"
      ),
      explanation = "loadgen sends on schedule whatever the server does. make load X=2 doubles every tenant's rate, and arrivals above the in-flight cap are counted in client_dropped_total.",
      doc = "Open and closed load models > Open loop"
    ),
    ChoiceQuestion(
      text = "You want a ramp-up load test to find the knee of api. Which load model do you need?",
      answer = "Open: a closed tool's rate falls as soon as the server slows, so it cannot push past the knee",
      others = listOf(
        "Closed: more virtual users is the same as more load",
        "Either one: the knee is a property of the server",
        "Closed, with zero think time"
      ),
      explanation = "A closed tool offers less load when R rises. The knee only shows under an arrival rate the server does not control.",
      doc = "Open and closed load models > Open loop"
    ),
    ChoiceQuestion(
      text = "A closed-loop tool at an intended 100 requests/s hits a 2 s stall. What does its p99 miss?",
      answer = "About 200 requests it would have sent during the stall, each with a long wait, so its p99 understates the user's p99",
      others = listOf(
        "Nothing: the 2 s request is in the histogram",
        "The errors during the stall",
        "Only the one request in flight"
      ),
      explanation = "The client cannot send while it waits. Fix: time each request from its intended send time, or use an open model.",
      doc = "Open and closed load models > Coordinated omission"
    ),
    ChoiceQuestion(
      text = "A report says: offered 500 requests/s, achieved 320, success 92%. What is the number users got?",
      answer = "About 294 good requests/s: achieved rate × success share",
      others = listOf(
        "500 requests/s: the offered rate",
        "320 requests/s: the achieved rate",
        "460 requests/s: 92% of the offered rate"
      ),
      explanation = "320 × 0.92 = 294.4. Then ask which server resource capped the rate at 320: that resource is the result of the test.",
      doc = "Open and closed load models > Reading a load test report"
    ),
    ChoiceQuestion(
      text = "A load test report has one line: 800 rps at p99 120 ms. What is missing?",
      answer = "The offered rate, the success share, p50 and p99 by endpoint, the server's USE during the run, and the client's CPU",
      others = listOf(
        "Nothing: rate and p99 are the two numbers that matter",
        "The mean latency",
        "The name of the tool"
      ),
      explanation = "Without the offered rate you cannot tell a closed loop that fell back. Without the success share, fast errors hide. Without USE, the number has no reason.",
      doc = "Open and closed load models > Reading a load test report"
    )
  )
}

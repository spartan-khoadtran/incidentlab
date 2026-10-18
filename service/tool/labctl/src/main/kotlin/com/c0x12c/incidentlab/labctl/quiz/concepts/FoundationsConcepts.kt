package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/foundations.md: one situation, four readings, one of them right. */
internal object FoundationsConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Statistics for latency
    ChoiceQuestion(
      text = "You benchmark one batch job three times. The runs reach 100, 200 and 400 records/s. Which mean says what throughput to expect over the three runs?",
      answer = "The harmonic mean, about 171 records/s: each run did the same work and the slow run took most of the time",
      others = listOf(
        "The arithmetic mean, 233 records/s, because that is what a mean is",
        "The geometric mean, 200 records/s, because throughput is a ratio",
        "The maximum, 400 records/s, because the other runs were noise"
      ),
      explanation = "Equal work, unequal time. Total work over total time is 3,000 records in 17.5 s, so 171 records/s, which is the harmonic mean.",
      doc = "Statistics for latency > Three means"
    ),
    ChoiceQuestion(
      text = "A release makes endpoint A 4x faster and endpoint B 4x slower (0.25x). What is the fair one-number summary of the release?",
      answer = "No change: the geometric mean of 4 and 0.25 is 1",
      others = listOf(
        "A 2.1x speedup, the arithmetic mean of 4 and 0.25",
        "A 4x speedup, since A is the one users notice",
        "A 4x slowdown, since the worst case rules"
      ),
      explanation = "Speedups are ratios. A 4x gain and a 4x loss should cancel, and only the geometric mean says so. The arithmetic mean rewards the big ratio.",
      doc = "Statistics for latency > Three means"
    ),
    ChoiceQuestion(
      text = "An endpoint has a mean of 80 ms and a standard deviation of 160 ms, so a CV of 2. What does that say before you open the histogram?",
      answer = "The spread is wider than random: expect a heavy tail or two humps, not one population",
      others = listOf(
        "The endpoint is stable, because the deviation is small against the SLO",
        "Nothing: standard deviation only works for CPU metrics",
        "The mean is wrong and should be recomputed"
      ),
      explanation = "CV 1 is the memoryless landmark. Above it the data has a tail or several modes. Split by a label or read the heatmap.",
      doc = "Statistics for latency > Spread"
    ),
    ChoiceQuestion(
      text = "Two workers have the same mean service time of 10 ms. Worker A has CV 0.1 and worker B has CV 1.2. Which one forms the longer queue at the same load?",
      answer = "B: the M/G/1 wait grows with (1 + CV²) / 2, so B waits about 2.4 times as long as A",
      others = listOf(
        "A: a steady worker queues more because it cannot skip ahead",
        "The same: queue length depends on utilization only",
        "Neither queues under 100% utilization"
      ),
      explanation = "(1 + 1.44) / 2 = 1.22 for B against (1 + 0.01) / 2 = 0.505 for A. CV 0 is M/D/1, half the M/M/1 wait. CV above 1 is worse than M/M/1.",
      doc = "Statistics for latency > Spread"
    ),
    ChoiceQuestion(
      text = "The mean of a product endpoint moved from 10 ms to 18 ms. p50 stayed at 2 ms and p99 stayed at 40 ms. What happened?",
      answer = "The mix changed: more requests miss the cache, and the two humps did not move",
      others = listOf(
        "The code got slower on every request",
        "The database got slower",
        "The histogram buckets were changed"
      ),
      explanation = "A bimodal mean lands between the humps and moves with the mix. A fixed p50 and a fixed p99 say both humps stayed put. Check the hit ratio.",
      doc = "Statistics for latency > Multimodal distributions"
    ),
    ChoiceQuestion(
      text = "A latency heatmap shows two clear bands, at 3 ms and at 60 ms. What is the next step?",
      answer = "Split the histogram by the label that explains the bands, for example cache outcome or route, with sum by (route, le)",
      others = listOf(
        "Report the mean, which sits between the bands",
        "Raise the bucket count until the bands merge",
        "Alert on p50, since it sits in the lower band"
      ),
      explanation = "Two bands are two populations. Find the dimension that separates them. The mean describes neither.",
      doc = "Statistics for latency > Multimodal distributions"
    ),
    ChoiceQuestion(
      text = "In one minute 98 requests took 10 ms and two took 5 s. Which summary did not move?",
      answer = "The median: it stays at 10 ms while the mean goes to about 110 ms",
      others = listOf(
        "The mean: two requests in 100 cannot move it",
        "The standard deviation: it only counts the fast requests",
        "p99: it ignores the two slowest requests"
      ),
      explanation = "Mean and standard deviation follow the outliers. The median does not. 2% of requests are slow, more than 1%, so p99 is 5 s: what one user in 100 gets.",
      doc = "Statistics for latency > Outliers"
    ),
    ChoiceQuestion(
      text = "A teammate wants to drop the slowest 1% of requests before computing the dashboard latency, to remove noise. What is wrong with that?",
      answer = "The slowest 1% is a real user experience and often the first sign of the next incident; look at those traces instead",
      others = listOf(
        "Nothing: outliers are measurement errors",
        "It only works if you also drop the fastest 1%",
        "It is fine as long as the mean is also shown"
      ),
      explanation = "An outlier is a request. Open its exemplar. Tail latency is the user's experience, and fan-out makes it most users' experience.",
      doc = "Statistics for latency > Outliers"
    ),

    // ---------------------------------------------------------------- Latency time scales
    ChoiceQuestion(
      text = "If one CPU cycle lasted one second, about how long would one rotational disk read take?",
      answer = "About a year",
      others = listOf(
        "About a minute",
        "About a day",
        "About a century"
      ),
      explanation = "10 ms × 3.3 billion is 33 million seconds, about one year. Memory is minutes, SSD is days, a disk is a year.",
      doc = "Latency time scales > Scaled to human time"
    ),
    ChoiceQuestion(
      text = "On the same scale, one cycle = 1 s, how long is a network round trip between continents?",
      answer = "About 10 years",
      others = listOf(
        "About 3 weeks",
        "About 6 minutes",
        "About 4 days"
      ),
      explanation = "100 ms × 3.3 billion is 330 million seconds, about 10 years. The same-datacenter trip is 3 weeks.",
      doc = "Latency time scales > Scaled to human time"
    ),
    ChoiceQuestion(
      text = "A request does 50 small queries of 0.5 ms round trip each. Which fix gains most?",
      answer = "Batch them into one or two round trips: the 25 ms are trips, not CPU",
      others = listOf(
        "Optimize the query plan of each one",
        "Give the service more CPU",
        "Move the service to a host with faster memory"
      ),
      explanation = "A round trip costs 5,000 memory reads. Fewer trips beat faster code at every level of the table.",
      doc = "Latency time scales > What the scale says"
    ),
    ChoiceQuestion(
      text = "Why does a 1 s timeout on a dependency empty a thread pool so fast?",
      answer = "A blocked thread gives up 3 billion cycles; at 50 ms per request it could have served 20 requests",
      others = listOf(
        "Because timeouts raise CPU usage",
        "Because 1 s is shorter than the garbage collector's pause",
        "It does not: a timeout frees the thread"
      ),
      explanation = "On the scaled clock 1 s is a century. Little's Law turns the hold time into pool size: rate × 1 s threads are stuck.",
      doc = "Latency time scales > What the scale says"
    ),

    // ---------------------------------------------------------------- Modeling and scaling laws
    ChoiceQuestion(
      text = "90% of a request's work can run in parallel. You go from 8 workers to 64. What speedup over 1 worker do you get at 64?",
      answer = "About 8.8x; the cap is 10x however many workers you add",
      others = listOf(
        "64x, one per worker",
        "About 57x, 90% of 64",
        "About 4.7x, the same as with 8 workers"
      ),
      explanation = "S(64) = 1 / (0.1 + 0.9 / 64) = 8.8x. S(8) is 4.7x. The serial 10% caps everything at 10x.",
      doc = "Modeling and scaling laws > Amdahl's law"
    ),
    ChoiceQuestion(
      text = "A service runs one Netty event loop thread for all CPU work, under a 2-core limit. What does Amdahl's law say about raising the limit to 4 cores?",
      answer = "Nothing changes for the event loop work: the serial part is one thread, and it is already under the limit",
      others = listOf(
        "Throughput doubles, since the limit doubles",
        "Latency halves, since each request gets two cores",
        "The event loop splits itself across cores"
      ),
      explanation = "The single thread is the serial share. More cores help only the parallel part. In this lab api runs one event loop, so one saturated thread shows as half of the 2-core limit.",
      doc = "Modeling and scaling laws > Amdahl's law"
    ),
    ChoiceQuestion(
      text = "A load test gives throughput 1.0 at 1 instance, 5.7 at 8, 8.0 at 16 and 7.8 at 64. What is going on?",
      answer = "Coherency cost: past the peak near 31 instances, each added instance lowers throughput (κ above 0)",
      others = listOf(
        "A measurement error at 64: throughput cannot fall with more instances",
        "Pure Amdahl: the serial share caps it, but it never falls",
        "The load tester ran out of users"
      ),
      explanation = "Amdahl flattens but never falls. A falling curve is the κ N (N − 1) term: cache invalidation, consensus, every node talking to every node.",
      doc = "Modeling and scaling laws > Universal Scalability Law"
    ),
    ChoiceQuestion(
      text = "In the Universal Scalability Law, what does σ (sigma) stand for in a real system?",
      answer = "Contention: locks, serial sections, one shared queue head, a single-threaded component",
      others = listOf(
        "Coherency: cache invalidation and consensus between nodes",
        "The scrape interval of the monitoring system",
        "The share of requests that time out"
      ),
      explanation = "σ is the Amdahl term (σ = 1 − p). κ is the coherency term, the cost of keeping N workers agreeing.",
      doc = "Modeling and scaling laws > Universal Scalability Law"
    ),
    ChoiceQuestion(
      text = "Two identical workers, each with its own queue, each at 80% utilization. You merge the queues into one. What happens to the mean wait in queue?",
      answer = "It drops by (1 + ρ) / ρ = 2.25x, from 40 ms to 17.8 ms at S = 10 ms, because no worker idles while the other has a line",
      others = listOf(
        "Nothing: utilization is unchanged",
        "It doubles, because the single queue is twice as long",
        "It drops to 0: M/M/2 has no queue below 100%"
      ),
      explanation = "c servers behind one queue beat c separate queues of the same servers. One thread pool beats one queue per thread.",
      doc = "Modeling and scaling laws > Queue models"
    ),
    ChoiceQuestion(
      text = "What does the D in M/D/1 change against M/M/1, and by how much?",
      answer = "The service time is fixed, with no variance, and the queue wait is exactly half",
      others = listOf(
        "The arrivals are deterministic, and the queue wait is zero",
        "The server is doubled, and the wait is a quarter",
        "Nothing measurable: D and M are two names for the same model"
      ),
      explanation = "Wq(M/D/1) = ρ / (2(1 − ρ)) × S against ρ / (1 − ρ) × S. Half of a queue is service-time variance.",
      doc = "Modeling and scaling laws > Queue models"
    ),
    ChoiceQuestion(
      text = "In A/S/c notation, what does M/M/2 describe?",
      answer = "Poisson arrivals, exponential service times, two servers behind one shared queue",
      others = listOf(
        "Two queues with one server each",
        "Two arrival streams into one server",
        "A model where M stands for the mean service time"
      ),
      explanation = "M means memoryless for both arrivals and service. c = 2 servers, one queue. The exact results: P(wait) = 2ρ² / (1 + ρ), Wq = ρ² / (μ(1 − ρ²)).",
      doc = "Modeling and scaling laws > Queue models"
    ),
    ChoiceQuestion(
      text = "A load test with 20 fixed virtual users shows latency rising gently as you slow the server. In production the same slowdown caused a collapse. Why?",
      answer = "The test is a closed system: throughput caps at users / response time, so it backs off. Real users are an open arrival stream and keep coming",
      others = listOf(
        "Production has a worse network",
        "The test used the wrong percentile",
        "A collapse needs at least 100 users"
      ),
      explanation = "Open arrivals do not care how long the queue is. A closed tester sends less when the server slows. This lab's loadgen is open for that reason.",
      doc = "Modeling and scaling laws > When a model lies"
    ),
    ChoiceQuestion(
      text = "During a retry storm you compute the M/M/1 wait from the utilization and get 50 ms. The real wait is 400 ms. Which assumption broke?",
      answer = "Poisson arrivals: retries arrive in waves tied to the timeout, far burstier than random",
      others = listOf(
        "The formula only works below 50% utilization",
        "Little's Law does not hold during incidents",
        "The service time became deterministic"
      ),
      explanation = "Burstier arrivals than Poisson mean longer queues than M/M/1 says. Use the model for direction and order of magnitude, never for the p99.",
      doc = "Modeling and scaling laws > When a model lies"
    )
  )
}

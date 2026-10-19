# Foundations: statistics, time scales and models

The math under the investigation guide. Three short topics: how to summarize latency without fooling yourself, how slow each layer of a computer really is, and the models that say when a system runs out of room. The drills of `make quiz` point at these sections as `Section > Subsection`.

## Statistics for latency

A latency number is a summary of many requests. Pick the wrong summary and the chart lies.

### Three means

| Mean | Formula | Use it for | It lies when |
|---|---|---|---|
| Arithmetic | sum / n | Things that add up: total time, bytes, cost | The data has a long tail or two humps |
| Geometric | n-th root of the product | Ratios and speedups: 2x, 0.5x, 8x | A value is 0 or negative |
| Harmonic | n / (sum of 1/x) | Rates measured over equal amounts of work: req/s per run | The amounts of work differ |

Arithmetic. Three requests take 100, 200 and 400 ms. The mean is 700 / 3 = 233 ms. Add one request of 5 s and the mean becomes 1,425 ms. No request took anything near that.

Geometric. One change makes service A 2x faster and service B 8x faster. The arithmetic mean says 5x. The geometric mean is the square root of 2 × 8, so 4x. Why the geometric one is right: a 2x slowdown (0.5x) and a 2x speedup (2x) should cancel out. Geometric: sqrt(0.5 × 2) = 1. Arithmetic: (0.5 + 2) / 2 = 1.25, a speedup that did not happen.

Harmonic. You run the same batch of 1,000 requests three times. The runs reach 100, 200 and 400 req/s. The arithmetic mean says 233 req/s. But the runs took 10 s, 5 s and 2.5 s. That is 3,000 requests in 17.5 s, so 171 req/s. The harmonic mean gives the same: 3 / (1/100 + 1/200 + 1/400) = 3 / 0.0175 = 171 req/s. The slow run holds more of the time, so it counts more.

When in doubt, do not average averages. Add the totals and divide once: total work / total time. That is right for any distribution.

### Spread

The standard deviation is the typical distance from the mean. Take each value minus the mean, square it, average the squares, take the root. Durations of 8, 10 and 12 ms have a mean of 10 ms and a standard deviation of 1.6 ms.

The coefficient of variation (CV) is the standard deviation divided by the mean. It has no unit, so a 10 ms endpoint and a 2 s batch compare on the same scale. The 8, 10, 12 ms set has a CV of 1.6 / 10 = 0.16. A mean of 100 ms with a standard deviation of 150 ms has a CV of 1.5.

CV 1 is a landmark. The exponential distribution, the memoryless one that queue models assume, has a standard deviation equal to its mean, so its CV is 1. CV well under 1 means a steady service time, close to a fixed cost. CV above 1 means the spread is wider than random: a heavy tail, or several humps. A latency series with CV above 1 is often more than one population. Split it, or read the histogram.

| CV | Reads as | Example |
|---|---|---|
| Near 0 | A fixed service time | A hash lookup, a fixed-size write |
| About 1 | Random, memoryless | What M/M/1 assumes |
| Above 1 | A heavy tail or several modes | Cache hit plus cache miss, GC pauses |

### Multimodal distributions

A cache gives two humps. A hit takes 2 ms and a miss takes 40 ms. With an 80% hit ratio the mean is 0.8 × 2 + 0.2 × 40 = 9.6 ms. No request takes 9.6 ms. The mean sits in the valley between the humps.

The percentiles land in the humps. p50 is a hit, so it says 2 ms. p99 is a miss, so it says 40 ms. The mean moves when the hit ratio moves, while both humps stay where they are. A mean that drifts with a fixed p50 and a fixed p99 means the mix changed, not the code.

How to see it: read the histogram or the heatmap, not one line. Then split by the dimension that explains the modes: cache outcome, route, tenant, status code, instance. In PromQL keep that label when you add the buckets: `sum by (route, le) (rate(http_server_duration_seconds_bucket[$__rate_interval]))`. One hump per label value means you found the dimension. In this lab a product request either hits Redis or misses and queries Postgres. So the product route has a fast path and a slow path. The Cache hit ratio panel of dashboard 01 gives the mix: `sum(rate(cache_requests_total{result="hit"}[$__rate_interval])) / sum(rate(cache_requests_total[$__rate_interval]))`.

### Outliers

One slow request moves the mean and the standard deviation. It does not move the median. Take 100 requests: 99 take 10 ms and one takes 5 s. The mean goes from 10 ms to (99 × 10 + 5,000) / 100 = 59.9 ms. The median stays at 10 ms. The standard deviation jumps from 0 to about 500 ms.

So the mean is the wrong summary for a decision, and the median hides the tail. p99 is the honest summary: it is what 1 user in 100 gets. With 2 slow requests in 100 instead of 1, p99 is 5 s. Tail latency is the user's experience. With fan-out it becomes most users' experience, because a request waits for its slowest call. The investigation guide covers that under tail amplification.

Do not delete outliers before you look at them. An outlier is a real request, and often the first sign of the next incident. Click its exemplar and open the trace.

| Summary | One outlier in 100 | Reads as |
|---|---|---|
| Mean | Moves a lot | The sum of all waiting, good for capacity math only |
| Median (p50) | Does not move | The typical request, blind to the tail |
| p99 | Moves to the outlier once slow requests are more than 1 in 100 | What the slowest 1% of users see |
| Standard deviation | Explodes | Only meaningful for one hump |

## Latency time scales

### Scaled to human time

One CPU cycle at 3 GHz takes about 0.3 ns. Call that one second. Every other wait then scales by the same factor, 1 s / 0.3 ns, about 3.3 billion. The real times below are typical orders of magnitude. Your hardware differs by 2 to 3x. The ranking does not.

| Event | Typical real time | If one cycle were 1 s |
|---|---|---|
| One CPU cycle | 0.3 ns | 1 s |
| L1 cache hit | 1 ns | 3 s |
| Main memory read | 100 ns | 6 minutes |
| SSD read | 100 us | 4 days |
| Rotational disk read | 10 ms | 1 year |
| Network round trip, same datacenter | 0.5 ms | 3 weeks |
| Network round trip, between continents | 100 ms | 10 years |
| TCP retransmit timeout (initial RTO) | 1 s | 100 years |

How to read one row: 100 ns × 3.3 billion = 333 s, about 6 minutes. 10 ms × 3.3 billion = 33 million seconds, about 1 year. The TCP row is the initial retransmit timeout, before any round trip is measured. RFC 6298 sets it at 1 s.

### What the scale says

Down the storage rows, each step costs 100 to 1,000 times the one before. Memory is minutes, SSD is days, a spinning disk is a year. One rotational disk read costs as much as 100,000 memory reads (10 ms / 100 ns). One SSD read costs 1,000 memory reads. One round trip inside the datacenter costs 5,000 memory reads.

So the big wins are not faster code. They are fewer trips: cache so you do not go to disk, batch so you do not cross the network 50 times, keep the working set in memory. An N+1 query with 50 round trips of 0.5 ms spends 25 ms waiting while the CPU is idle. On the scaled clock that is more than 2 years of a core doing nothing.

A 1 s timeout is a lifetime. A thread that blocks for 1 s gives up 3 billion cycles. At 50 ms per request, that thread could have served 20 requests. This is why a slow dependency empties a thread pool so fast. Little's Law, in the investigation guide, turns that small latency rise into a full pool.

## Modeling and scaling laws

Three models, each one a line of algebra. They do not give you the p99. They tell you which way a change pushes the system, and by about how much.

### Amdahl's law

Split a job into a part that can run in parallel (share p) and a part that cannot (share 1 − p). With N workers, the speedup is:

S(N) = 1 / ((1 − p) + p / N)

Worked example: p = 0.9 and N = 8. S = 1 / (0.1 + 0.9 / 8) = 1 / 0.2125 = 4.7x, not 8x. As N grows, p / N goes to 0 and the speedup stops at 1 / (1 − p) = 10x. With a thousand workers it is still under 10x.

| Serial share (1 − p) | Cap on the speedup |
|---|---|
| 50% | 2x |
| 10% | 10x |
| 5% | 20x |
| 1% | 100x |

What the serial part is in a service: the single event loop thread, a global lock, one Kafka partition, one DB primary, the parse and auth work before the fan-out. Scaling pods multiplies the parallel part only. In this lab, api runs one Netty event loop thread, so CPU work on it is serial whatever the CPU limit says.

### Universal Scalability Law

Amdahl says the gain flattens. In real systems throughput also falls when you add workers past a point. The Universal Scalability Law (USL) adds that second cost:

C(N) = N / (1 + σ (N − 1) + κ N (N − 1))

- C(N): throughput with N workers, relative to one worker.
- σ (sigma): contention, the serial share. Locks, serial sections, one shared queue head, a single-threaded component. With κ = 0 this is Amdahl's law with σ = 1 − p.
- κ (kappa): coherency, the cost of keeping N workers agreeing. Cache line invalidation between cores, consensus and replication between nodes, a mesh where every node talks to every node. It grows with N squared, so it wins in the end.

Throughput peaks at N* = sqrt((1 − σ) / κ) and falls after it.

Worked example: σ = 0.05 and κ = 0.001.

| N | C(N) |
|---|---|
| 1 | 1.0 |
| 8 | 5.7 |
| 16 | 8.0 |
| 31 | 9.0, the peak: N* = sqrt(0.95 / 0.001) = 30.8 |
| 64 | 7.8 |

At N = 16: 16 / (1 + 0.05 × 15 + 0.001 × 16 × 15) = 16 / (1 + 0.75 + 0.24) = 16 / 1.99 = 8.0. Past 31 workers, each added worker makes the system slower. How to use it: run a ramp load test at 2, 4, 8 and 16 workers and fit σ and κ to the throughput you get. Throughput that goes down when you add instances is κ. More instances will not fix it.

### Queue models

A queue model is named A/S/c: the arrival process, the service time distribution, the number of servers. M means memoryless (Poisson arrivals, exponential service), D means deterministic (a fixed time), G means any distribution. ρ (rho) is the utilization, λ / (c μ). λ is the arrival rate and μ = 1 / S is the service rate of one server.

The investigation guide covers M/M/1 under Linking RED and USE. Its subsection on why latency rises before 100% has the table of response time against utilization. Here is the wait in queue alone, Wq, so the models compare:

| Model | Wait in queue Wq | At ρ = 0.8, S = 10 ms |
|---|---|---|
| M/M/1 | ρ / (1 − ρ) × S | 40 ms |
| M/D/1 | ρ / (2 (1 − ρ)) × S | 20 ms |
| M/G/1, service time with any CV | ρ / (1 − ρ) × S × (1 + CV²) / 2 | 100 ms at CV = 2 |
| M/M/2, one shared queue | ρ² / (μ (1 − ρ²)), with ρ = λ / (2 μ) | 17.8 ms at λ = 160/s |

M/D/1 has exactly half the queue wait of M/M/1. Only one thing changed: the service time has no variance. The M/G/1 row shows why. It is the M/M/1 wait times (1 + CV²) / 2, where CV is the coefficient of variation of the service time. CV 1 gives a factor of 1 (M/M/1). CV 0 gives 1/2 (M/D/1). CV 2 gives 5/2, so 2.5 times the M/M/1 wait at the same load.

So the spread of the service time makes a queue as much as the load does. A slow path that runs 1 request in 20 raises the CV, and the CV raises the wait. Removing that slow path can shorten the queue more than shedding load.

M/M/2 puts two servers behind one queue. The exact results for c = 2 are P(wait) = 2 ρ² / (1 + ρ) and Wq = ρ² / (μ (1 − ρ²)). Worked example: S = 10 ms, so μ = 100/s. λ = 160/s, so ρ = 160 / 200 = 0.8. P(wait) = 2 × 0.64 / 1.8 = 0.71. Wq = 0.64 / (100 × 0.36) = 0.0178 s = 17.8 ms.

Compare with the same 160/s split over two separate M/M/1 queues of 80/s each. Each one is at ρ = 0.8, so each one waits 40 ms. One shared queue: 17.8 ms. The shared queue wins by (1 + ρ) / ρ = 2.25x. Why: a server never sits idle while a request waits in the other server's line. The general rule: c servers behind one queue beat c separate queues of the same servers. One thread pool with one queue beats a queue per thread. Kafka partitions are separate queues, so an idle consumer cannot help a slow partition.

One more fact from the same example. One server twice as fast (S = 5 ms) at the same 160/s waits 20 ms and answers in 25 ms. The pair answers in 27.8 ms. A faster single server beats two slower ones. Scale up before you scale out, when you can.

### Bounded queues and load shedding

The models above have a queue with no limit. A real server needs a limit. Under overload a queue with no limit grows until memory runs out. Every request in it waits longer than its client does. The client times out, often retries, and the server spends its work on answers nobody reads. The pool is 100% busy and the goodput is close to 0.

A limit turns a long wait into a fast error. A thread pool with 8 threads and a queue of 100 holds 108 requests at most, so it rejects request 109 at once. That rejection is the Errors column of USE for a software resource: rejected tasks for a pool, a 429 for a rate limiter. A limit with no queue at all (the limit equals the number of workers) is a semaphore or a bulkhead: when every permit is taken, the next request fails at once.

Size the queue from the time a request may wait, not from memory. A request at position n waits about n / X before it starts, where X is the throughput of the pool (Little's Law). Worked example: the pool serves X = 200 requests/s and the client timeout leaves 500 ms for the wait. Then n = 200 × 0.5 = 100. A request at position 101 or later times out before it starts, so a longer queue only holds dead work.

The order matters under overload too. FIFO serves the oldest request first, and under overload the oldest request is the one most likely abandoned by its client. LIFO serves the newest first: fresh requests finish in time and only old ones fail. A common setup keeps FIFO while the queue is short, switches to LIFO when it stays long, and drops a request that waited past a target time before any work starts on it. The same check works without a queue change: when a request starts, compare its age with the client deadline and drop it if the client has already given up.

### When a model lies

- Arrivals are not Poisson in a retry storm. Retries arrive in waves tied to the timeout, and a restart brings a thundering herd. Cron jobs fire at the top of the minute. Real arrivals are often burstier than Poisson, so real queues are longer than M/M/1 says.
- Service times are not exponential. A cache gives two modes and a GC pause gives a heavy tail. A CV above 1 means worse than M/M/1. A CV near 0 means M/D/1, which is better.
- Open against closed. M/M/1 is an open system: arrivals do not care how long the queue is. A closed system has N users, and each one waits for the answer before it sends again. A load tester with fixed concurrency works this way, and so does an upstream thread pool. A closed system cannot blow up. Throughput is N / (response time + think time), so when the server slows down, the users send less. A load test with 20 fixed users never shows the collapse that an open stream of users causes. In this lab loadgen is an open Poisson generator for that reason.
- Steady state only. The formulas need ρ under 1 and a long run. At ρ = 1 or more the queue grows without end. The formula then divides by zero or returns a negative number, and neither means anything.

Use the models for direction and order of magnitude. Going from 40% to 80% utilization multiplies the M/M/1 wait by 6 (from 0.67 S to 4 S). The exact factor differs in a real system. The direction does not: the wait grows much faster than the load. A model never gives the p99 of your system. A load test does.

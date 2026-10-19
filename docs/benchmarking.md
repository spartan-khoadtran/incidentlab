# Benchmarking and load models

A benchmark is an experiment, so the rules of the scientific method apply. One question, one change, a prediction that can fail, a written setup. This file lists the mistakes that make a benchmark number worthless, and the two load models that decide what a number means. The drills of `make quiz` point at its sections as `Section > Subsection`.

## Benchmarking mistakes

### Casual benchmarking

Run once, with no plan, no repeat and no record of the setup. The number cannot be reproduced, so it cannot be compared with anything.

| Casual | Planned |
|---|---|
| "I ran it and got 800 rps" | The question: can api hold 4 times the base load with the pool at 4? |
| No record of versions, limits, data size | Written down: image tag, `cpus`, `mem_limit`, row counts, the client machine |
| One run | Three or more runs after a warm-up, with the spread |
| One number | Offered rate, achieved rate, success share, p50, p99, the server's USE, the client's CPU |
| Nobody can repeat it | A script that anyone runs and gets the same shape of result |

Write the plan before the run. The plan is short: the question, the setup, the load model, the metrics to keep, the stop rule. Most of the mistakes below are a missing line in that plan.

### Benchmarking the wrong thing

The tool measures what you point it at. That is often not the thing you wanted to know.

| You measured | You wanted | How to notice |
|---|---|---|
| A hello-world endpoint | The checkout path with its queries, its pricing call and its Kafka produce | The server's pool and DB metrics stay flat during the run |
| A warm cache only | What happens on a miss | `GET /products/1` with Redis warm measures Redis. The same id for every request never misses |
| A client that is itself the bottleneck | The server's limit | The client's CPU is at 100%, or its in-flight count sits at its own connection limit |
| A dataset that fits in memory | Production, where it does not | Disk reads are zero during the run. The page cache holds the whole table |
| One request at a time | Many users at once | Pools and locks never fill. With a concurrency of 1 there is no contention to measure |

In the lab, loadgen's users mostly browse the first 50 products, so most product reads are cache hits. A benchmark of `/products` on the lab measures Redis more than Postgres. loadgen's own CPU is `rate(process_cpu_seconds_total{job="loadgen"}[1m])`. Check it before you trust a number from it.

### Ignoring errors

Errors are fast. A request that fails right away takes 2 ms. A request that succeeds takes 100 ms. So a run with 30% failures can show a better mean and p99 than a healthy run. In a closed loop it also shows a higher rate, because a fast error frees the client sooner.

Worked example at 1,000 requests/s:

| | Healthy run | Run with 30% errors |
|---|---|---|
| Requests/s | 1,000 | 1,000 |
| Success share | 100% | 70% |
| Successful requests/s | 1,000 | 700 |
| Mean latency | 100 ms | 0.3 × 2 + 0.7 × 100 = 70.6 ms |
| What the report says | 1,000 rps at 100 ms | 1,000 rps at 70.6 ms, which looks better |

The second run serves 300 fewer users per second and reports a better number. Always report the success share next to the latency, and compute the success throughput: achieved rate × success share. Read the latency of successes and the latency of errors as two series, as the guide says under Duration.

PromQL in the lab: `sum by (outcome) (rate(client_requests_total[1m]))` splits the client's rate by outcome, and `client_duration_seconds_bucket{outcome="ok"}` holds the latency of successes only.

### Ignoring variance

One run is an anecdote. Warm up, run several times, and report the spread.

- Warm up first. The JVM compiles hot code while it runs, so the first minute is slower than the tenth. Caches fill, connections open, and the OS page cache loads the table. Throw the warm-up away.
- Run at least three times. Report the median and the min and max, or every run.
- Call B faster only when the two spreads do not overlap. If A gives 780 to 820 rps and B gives 800 to 840 rps, B is not proven faster.
- Keep the setup identical between runs, including the data. A second run against a database that the first run filled with orders is a different experiment.

| Runs of A (rps) | Runs of B (rps) | Conclusion |
|---|---|---|
| 790, 805, 810 | 900, 910, 925 | B is faster. The spreads are far apart |
| 780, 805, 820 | 800, 815, 840 | Not proven. The spreads overlap |
| 800 | 830 | Nothing. One run each |

### Changing many things at once

A benchmark with two changes gives one number and two possible causes. Change one thing between two runs, and keep everything else fixed, including the load.

| Run | Pool size | Pricing timeout | p99 |
|---|---|---|---|
| Baseline | 4 | 2 s | 400 ms |
| Change 1 | 8 | 2 s | 180 ms |
| Change 2 | 4 | 500 ms | 390 ms |
| Both | 8 | 500 ms | 175 ms |

Four runs, and the answer is clear: the pool is the cause and the timeout does almost nothing. With only the Both run, the timeout change would have shipped as part of the fix.

The same rule applies during an incident: see `Working an incident > One change at a time` in methods.md.

### Active against passive benchmarking

| | Passive | Active |
|---|---|---|
| What you do | Start the tool, wait, read the number | Watch RED on the client and USE on the server while the run goes |
| What you learn | A rate and a latency | What limits the rate: the pool, the CPU, the client, the DB |
| What can go wrong | The number is the client's limit, or an error rate nobody saw | Nothing hidden, because you looked |
| What you can say after | "It did 800 rps" | "It did 800 rps, capped by the pool at 4 of 4 with 6 waiting" |

A benchmark result without the limiting resource is a number with no reason. The next question is always why the rate did not go higher. Only an active run can answer it.

In the lab: `make load X=4`, then watch two boards while it runs. Dashboard 02, row 1 shows the pool: `db_pool_in_use` against `db_pool_max`, and `db_pool_waiting`. Dashboard 04, row 1 shows the cgroup: `cgroup_cpu_usage_seconds_total` against `cgroup_cpu_limit_cores`, and throttled periods. The one that is full is the limit, and the result of the test is that resource, not the rps.

## Open and closed load models

The load model decides what a benchmark number means, and what a server looks like when it is overloaded.

### Closed loop

N clients. Each one sends the next request only after the reply arrives, then waits a think time Z, then sends again. The number of requests in the system can never go above N.

Throughput: X = N / (R + Z), where R is the response time.

Example: N = 20 clients, R = 50 ms, Z = 150 ms. X = 20 / 0.2 s = 100 requests/s. The server slows down and R becomes 450 ms. X = 20 / 0.6 s = 33 requests/s. Nobody changed the clients, and the offered load fell by two thirds.

That is the trap of the closed model. When the server slows, the offered load falls by itself. The queue never grows past N, so the server never looks overloaded. Latency grows a little, throughput falls, and every panel stays calm.

Where a closed loop is right: a fixed set of batch workers, or a thread pool that calls a database. A consumer that takes the next message after the last one is closed too. A load tool with a fixed number of virtual users or connections is closed, whether it says so or not.

Little's Law still holds: L = X × R, and L is at most N. With Z = 0, every client is always waiting for a reply, so L = N and X = N / R.

### Open loop

Arrivals do not depend on the server. A user who opens the site does not know that others are waiting. Requests arrive at their own rate, whatever the server does.

When the server slows, the queue grows and latency climbs. Above capacity the queue grows without end, until requests time out. That is what a real overload looks like, and it is the shape the guide describes under Causal chain.

The lab's loadgen is open. Every tenant has its own Poisson arrival process. Arrivals come at random with a fixed mean rate, each one independent of the last. The gap between two arrivals is drawn from an exponential distribution. The base is 50 requests/s across the 4 tenants, and `make load X=2` doubles every tenant's rate. A slow api does not slow loadgen down. So a saturated pool shows as waiting and a rising p99, not as a lower rate. loadgen caps its own in-flight requests and counts every arrival above the cap in `client_dropped_total`. A run with drops is already past the server's limit.

| | Closed loop | Open loop |
|---|---|---|
| Who sets the rate | The server, through R | The arrival process |
| When the server slows | Rate falls, queue stays at most N | Rate stays, queue and latency grow |
| Requests in flight | At most N | No limit |
| What overload looks like | Calm. Lower throughput, a bit more R | Loud. Rising p99, timeouts, in-flight climbing |
| Good for | Batch jobs, worker pools, finding the service time of one request | Web traffic, APIs, finding the knee and the behaviour past it |
| In the lab | The worker, which takes the next message of a partition after the last one | loadgen, Poisson per tenant |

A ramp-up load test, which the guide lists under Deeper methods, needs an open model. A closed tool cannot push past the knee, because its rate falls as soon as the server slows.

### Coordinated omission

A closed-loop client cannot send while it waits. So the requests it would have sent during a stall are never measured. The client and the server work together to leave out the worst samples.

Worked example. Intended rate 100 requests/s for 60 s, so 6,000 intended requests. The server stalls for 2 s once.

| | What the closed client measures | What the users would have seen |
|---|---|---|
| Requests during the stall | 1, the one in flight, at 2 s | 200: the one in flight plus 199 that queue behind it |
| Samples | 5,801 | 6,000 |
| Samples delayed by the stall | 1 | 200, with waits from 2 s down to 0.01 s |
| Delayed share | 0.02% | 3.3% |
| p99 | Normal | About 1.4 s |

The hidden requests are about stall × intended rate: 2 s × 100 requests/s is 200. Each one waited 10 ms less than the one before it. The slowest 1% of 6,000 is 60 requests, so the true p99 is the 60th wait: about 1.4 s. The measured p99 understates the user's p99 by a lot, and the longer the stall, the bigger the gap.

The fix is one of two:

- Time each request from its intended send time, not from the moment it really left. A request that was due at t = 10.00 s and left at t = 11.50 s has already waited 1.5 s.
- Use an open model. An open generator sends on schedule whatever the server does, so the queued requests are real requests with real latencies. The lab's loadgen starts the clock of `client_duration_seconds` when an arrival is due, give or take a few milliseconds. Time spent waiting inside the client counts.

### Reading a load test report

A report with one number says nothing. These fields must be in it:

| Field | Why | Red flag |
|---|---|---|
| Offered rate | What the test tried to send. `client_target_rps` in the lab | Missing: you cannot tell a closed loop that fell back from a server that kept up |
| Achieved rate | What really arrived at the server | Far below the offered rate: the server, the client or the network capped it |
| Success share | Errors are fast and make everything else look better | Below 100% with no comment next to the latency |
| p50 and p99 by endpoint | The mean hides the tail, and the total hides the slow endpoint | One latency number for the whole run |
| The server's USE during the run | Which resource capped the rate: pool, CPU, DB, cgroup throttling | No server metrics: the number has no reason |
| The client's own CPU | A client at 100% measured itself | Missing, or above about 70% |

How to read it, in order:

1. Success share first. A run with errors is a different experiment, and its latency is not comparable.
2. Offered against achieved. A gap in an open model is a growing queue or dropped requests. A gap in a closed model is the server slowing the clients down.
3. Success throughput: achieved rate × success share. That is the number users got.
4. p99 by endpoint, against the SLO and against the baseline.
5. The resource that was full on the server. That is the result of the test. The rps is only where it stopped.

Example: offered 500 requests/s, achieved 320, success 92%, the DB pool full with 8 waiting. Success throughput is 320 × 0.92 = 294 requests/s. The result is not 320 rps. The result is that the DB pool caps api at about 300 good requests/s.

# Capacity: ramp tests and closed-loop clients

This file covers two exercise modes on the live lab. `make ramp` raises the load in steps and asks where the service saturates. `make bench` runs closed-loop clients for a while and shows why their numbers look better than what users get. The theory drills of `make quiz` point at these sections too.

## Capacity from a ramp test

A ramp test raises the offered load in steps and measures each step.

It answers three questions. How much load does the service take before latency bends? How much does it serve at most? Which resource fills first?

The laws below turn those answers into numbers you can plan with.

### Utilization Law

U = X × S

- U: the utilization of a resource, the share of time it is busy.
- X: the throughput, completions per second.
- S: the mean service time the resource spends on one completion.

A resource with c parallel units (c connections, c cores) splits the work: U = X × S / c.

The service demand D is the time one user request needs from a resource, over all its visits. In this lab a checkout holds a DB connection about 50 ms, and 30% of requests are checkouts. So one request needs 0.3 × 50 ms = 15 ms of connection time on average.

| Rate | Pool of 4: U = X × 0.015 / 4 |
|---|---|
| 50 rps | 19% |
| 150 rps | 56% |
| 250 rps | 94% |
| 267 rps | 100% |

U cannot pass 100%, so the highest rate is X max = c / D. For the pool of 4 that is 4 / 0.015 = about 267 rps. The resource with the lowest X max is the bottleneck. The law also works the other way: X = U × c / S.

### Finding the knee

The knee is the load where latency starts to bend up. Past it, a small step in load adds a lot of latency. Throughput still grows a little after the knee, then it flattens at X max.

How to run a ramp:

1. Start low, at the normal load, so the first step is the baseline.
2. Raise the offered load in equal steps of time. Hold each step long enough for the queue to settle and for the rate window to fill.
3. Leave the first seconds of each step out of its window. They still carry the step before.
4. For each step, record offered rate, achieved rate (successes), p99 and the error share.

How to read the table:

- Offered against achieved. Below the knee, achieved follows offered. At X max, achieved stops and the rest queues or fails.
- p99 against the first step. Queueing delay grows fast as utilization rises, well before 100% (see [Why latency rises before 100%](investigation-guide.md#why-latency-rises-before-100)). So p99 bends before throughput flattens.
- Errors. Timeouts and pool timeouts start once the queue is longer than the timeouts allow.

| Step | Offered | Achieved | p99 | Errors | Reading |
|---|---|---|---|---|---|
| 1 | 50 | 50 | 80 ms | 0% | Baseline |
| 4 | 125 | 125 | 110 ms | 0% | Still flat |
| 5 | 150 | 149 | 180 ms | 0% | Bending, 2.3x the baseline |
| 6 | 200 | 196 | 420 ms | 0.2% | The knee: above 3x the baseline |
| 7 | 250 | 203 | 1.9 s | 6% | Saturated: achieved is flat |

Plan with the knee, not with the maximum. At the maximum the service has no room for a burst. The knee is also the denominator for a whole service's utilization: 150 rps against a knee at 200 rps is 75% busy.

### Headroom and the next saturation

Headroom is the room left between the busiest normal load and the capacity. Two ways to say it:

| Measure | Formula | Peak 150 rps, knee 200 rps |
|---|---|---|
| Share of capacity still free | 1 − peak / capacity | 25% |
| How much traffic can still grow | capacity / peak − 1 | 33% |

The two numbers differ, so always say which one you mean. Traffic growth is what a planner asks about. The free share is what a dashboard shows.

Fixing the bottleneck moves it. The next resource with the lowest X max becomes the new limit. So before a fix, compute X max for every resource on the request path, not only the one that is full.

| Resource in this lab | Demand per request | Units | X max |
|---|---|---|---|
| DB pool (default 4) | 0.3 × 50 ms | 4 connections | about 267 rps |
| Kafka worker | 0.3 messages | about 113 messages/s | about 377 rps |
| api CPU (2 cores) | 0.06 cores at 50 rps = 1.2 ms of CPU | 2 cores | about 1,667 rps |

Raise the pool and the worker is next at about 377 rps. The worker is async, so its saturation does not show in api p99. It shows as consumer lag, and orders get late while the api looks healthy.

### The ramp in this lab

`make ramp` runs 8 steps: 50, 75, 100, 125, 150, 200, 250 and 300 rps. Each step lasts 60 s by default (`STEP=45` changes it, from 30 to 300 s). The ramp raises loadgen's `surge_multiplier`, so it needs the normal load: run `make load X=1` first. After the last step the load goes back to 50 rps.

A hidden setup changes every run. It can shrink the DB pool, lower the api CPU limit, or change nothing. When the setup is not the pool, the pool is opened to 24, so it does not fill inside the range.

What to watch, step by step:

- Client requests/s (dashboard 01, row 1) against api requests/s by status (row 3).
- api p99 and in-flight (row 3). An annotation on the charts marks the start of each step.
- DB pool in use, max and waiting (dashboard 02, row 1). CPU usage against the limit and throttled periods (row 3).

`make answer` reads one row per step from Prometheus, then asks three questions. Each window skips the first 10 s of its step.

| Question | Right when | Points |
|---|---|---|
| The knee, in rps | Within one step of the first step with p99 above 3 times step 1's, or errors above 1%. Type 0 if there was none | 2 |
| The highest successful throughput | Within 10% of the highest api 2xx rate of any step | 2 |
| The limiting resource | The hidden one. With no knee, "nothing within the range" is right too | 1 |

You can answer before the last step. Then only the steps that ran count. The step table prints after the score, with the knee marked. If Prometheus does not answer, nothing is asked and nothing is scored.

## Closed-loop clients in this lab

loadgen is open by default: arrivals come at a set rate, whatever the service does (see [Open loop](benchmarking.md#open-loop)). A closed-loop client is different. It sends one request, waits for the answer, then sends the next. Many benchmark tools work this way by default.

### What make bench does

`make bench` switches loadgen to closed-loop clients for a while. `CLIENTS=20` and `SECONDS=120` are the defaults. The clients use the same mix of products and checkouts and the same `client_*` metrics.

`client_closed_clients` shows the count, and 0 means the open load. The bench has no score.

With N clients and no think time, Little's Law gives N = X × R. So R = N / X and X = N / R. The clients push the rate up until a resource is full, then the rate stays at that resource's X max.

In this lab, 20 clients fill the pool of 4. The rate climbs to about 267 rps, the pool's X max. The mean time is then about 20 / 267 = 75 ms, and api in-flight sits near 20.

After the bench, `make answer` (or `make bench` again) prints a report. It compares the 2 minutes before with the bench window:

| Row | Before | Bench |
|---|---|---|
| Rate | The open load, about 50 rps | Whatever the bottleneck allows |
| p50, p99 | At the client and at api | At the client and at api |
| Error share | Client outcomes, api 5xx | Same |
| Mean time, in-flight | Client mean, api in-flight | Checks N = X × R |

### Why the two p99 values differ

The bench reaches about 267 rps with a pool of 4, and its p99 looks fine. A ramp with a pool of 4 shows a far higher p99 or timeouts near the same rate. Both are right about what they measured, but only the ramp measured what users get.

Two things hide the problem in the bench.

The first is the cap on the queue. No more than N requests are ever inside, so the mean time stays near N / X. Open arrivals at the same rate put no cap on the queue. At X max their queue grows until timeouts end it.

The second is coordinated omission. When the service stalls, each closed client stops and waits. The requests it would have sent during the stall are never sent, so they are never measured. Each client records one slow sample per stall.

| A 2 s stall in a 60 s run | Open, 200 rps | Closed, 20 clients at 100 ms |
|---|---|---|
| Requests that meet the stall | 400 or more | 20, one per client |
| All requests in the run | 12,000 | about 11,620 |
| Share of slow requests | 3.3% or more | 0.17% |
| p99 | 1.4 s or more | about 100 ms |

In the closed case, 20 slow samples are under 1% of the run, so p99 shows the normal time. The worked example and the two ways to fix a load test are in [Coordinated omission](benchmarking.md#coordinated-omission).

That is why the lab's loadgen is open. It keeps its arrival rate when the service slows down. The queue and its latency show on the dashboards the way users would feel them. The two models side by side are in [Open and closed load models](benchmarking.md#open-and-closed-load-models).

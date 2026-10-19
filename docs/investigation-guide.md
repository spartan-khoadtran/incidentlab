# Investigating incidents with RED and USE

This is the study guide for incidentlab. The incident exercises on the live lab (`make new`, then `make answer`) and the theory drills (`make quiz`, no lab needed) point at its sections, written as `Section > Subsection`. The section [In this lab](#in-this-lab) maps every layer in the guide to a dashboard row.

## Overview

RED and USE are two checklists that complete each other. RED asks whether the service is serving well. USE asks which resource is the bottleneck. The usual order: RED to find where the problem is, USE to find why. RED goes first because it is short: three numbers per service, and they say whether users hurt. USE has many resources per service, so you check them only where RED points.

| | RED | USE |
|---|---|---|
| Applies to | A service, an endpoint, anything that takes a request and returns a response | A resource: hardware, OS, runtime, software resource (pool, lock, queue), cloud quota |
| Point of view | The workload, seen from the caller | The resource, seen from inside |
| Three metrics | Rate, Errors, Duration | Utilization, Saturation, Errors |
| Answers | Is there a problem? In which service? Is it load or architecture? | Why is it slow? Which resource is the bottleneck? |
| Blind spot | Does not name the resource. Does not see errors that happen before the request reaches the service | Does not say whether users are affected. A busy resource is not always a problem |

Three ideas run through this guide:

- Both RED and USE must be read at many layers. Numbers that differ between layers are clues, not noise (see [RED at many layers](#red-at-many-layers) and [USE at many layers](#use-at-many-layers)).
- Saturation in USE is what creates Duration in RED. Little's Law links Rate, Duration and the number of requests in flight (see [Linking RED and USE](#linking-red-and-use)).
- The two methods are a first pass: fast and wide. After them come deeper methods: workload characterization, drill-down, latency analysis, profiling (see [Combining with other methods](#combining-with-other-methods)).

## RED method in detail

For each service, watch request rate, errors and duration. RED measures user health; USE measures machine health. The first step: draw which service calls which (a service map; distributed tracing usually draws it for you, on Datadog it is the Service Map). Then make sure every node has all three metrics. Every arrow "A calls B" on that map is also a pair of adjacent layers to compare in [RED at many layers](#red-at-many-layers).

### Rate

Requests per second.

- Split by endpoint or operation. The total rate of a service is rarely enough: `/health` at 1000 rps hides `/checkout` at 20 rps. If `/checkout` drops by half, the total moves by 1%, and nobody sees it on the chart. In PromQL, `sum by (route)` gives one line per endpoint.
- Split by the dimensions you need for workload characterization: client or tenant, region, app version, status code. When the rate goes up, you will need to know who is calling more.
- Read against a baseline. Compare with the same hour yesterday or last week, because traffic repeats by day and by week. Without a baseline, 300 rps means nothing: it is high at 3 am and low at noon. In PromQL, `offset 1w` draws last week's line on the same chart.
- A drop in rate is a symptom too. A broken upstream, a wrong DNS record or a crashing client all stop requests from reaching the service. The service looks "healthy" because it has nothing to do.
- Rate is the clue for load versus architecture. Rate flat and duration up: the problem is in the service itself, because the same work now takes longer. Rate and duration up together: it may be load. It is only a maybe, because retries also raise the rate (see [Causal chain](#causal-chain)). Check with workload characterization.

### Errors

Failed requests. Agree on what "error" means before you measure. If you do not, every dashboard shows a different number.

| Type | Count in the error rate? | Note |
|---|---|---|
| 5xx | Yes | Server-side error |
| Timeout | Yes | Often has no status code. The client sees it, the server may not |
| 200 with an error in the body | Yes, if you can measure it | Common with GraphQL and older APIs. Needs instrumentation in the app |
| 4xx in general | Usually not | Client-side error. Still track it on its own: a bad deploy can produce 400s in bulk |
| 429 | Track on its own | A sign of rate limiting, which is saturation |
| 401/403 jumping | Track on its own | Expired tokens, wrong auth config |
| Fallback or degraded response | Track on its own | A technical success, but the user got a worse result |

- Watch both the ratio and the count. At low traffic, 2 errors out of 10 is 20% but may not matter. At high traffic, 0.1% can be thousands of users.
- Errors raise latency. A request can finish fast but with an error, get retried, and the latency adds up on the caller's side.

### Duration

Time per request.

- Use the distribution. Read p50, p95, p99, not the average. Example: 98 requests take 10 ms and 2 take 5 s. The average is about 110 ms and looks fine. But p99 is 5 s, so 2% of users wait 5 seconds.
- Split the duration of successful requests from the duration of failed ones. Failed requests are often very fast (fail fast) or very slow (timeout). When a dependency dies and the service answers 500 right away, the combined latency goes down, and the system looks better than before.
- Percentiles cannot be averaged. A percentile is a position in the sorted list of all requests. An average of two positions is not a position. Example: pod A serves 995 rps, all under 100 ms. Pod B is broken: it serves 5 rps, and every request takes 2 s. The average of the two p99s is about 1 s. But B's requests are only 0.5% of the total. So the real p99 of the service is under 100 ms. How: add up the histogram buckets of every instance first, then take the percentile once: `histogram_quantile(0.99, sum by (le) (rate(x_bucket[1m])))`.
- Histogram buckets must cover the latency range. A histogram only counts how many requests fall into each bucket. To get p99, Prometheus assumes the values spread evenly inside the bucket. Say one bucket goes from 1 s to 10 s. Then a real p99 of 1.2 s can show as about 5 s. How: put bucket edges close together around your SLO and your normal p99.
- Duration is only recorded when the request ends. During an incident, requests that are still hanging are not counted yet, so p99 can look fine until they time out. Also watch the number of requests in flight to see it sooner.
- With little traffic, percentiles are unstable. p99 needs about 100 requests to have one slower request above it. At 30 requests per minute, p99 is just the slowest request of the minute. One bad request moves the line. Use a longer time window, like `[10m]` instead of `[1m]`.

### RED for other workloads

| Workload | Rate | Errors | Duration |
|---|---|---|---|
| HTTP, gRPC | Requests/s by endpoint | 5xx, timeouts, errors in the body | Response time by endpoint |
| Database | Queries/s by query type | Failed queries, deadlocks, timeouts | Query latency |
| Queue consumer (Kafka, SQS) | Messages consumed/s | Failed messages, messages sent to a DLQ | Processing time per message, plus consumer lag (see [The async layer](#the-async-layer)) |
| Batch job | Jobs per hour, or records/s | Failed jobs | Job run time, and how late the data is (freshness) |

## USE method in detail

For each resource, check utilization, saturation and errors. Use it early in an investigation to find system-wide bottlenecks. USE loops over resources, not over tools, so it produces a complete list of questions, even for resources that have no metric yet.

### Three metrics

- Utilization: the share of time the resource was busy during a period. For a capacity-type resource like memory, the share of capacity in use.
- Saturation: how much work is waiting for the resource, usually in a queue. Another name for it is pressure.
- Errors: the count of error events.

### Order of checks

1. Errors first. They are objective, quick to read, and rule things out early.
2. Saturation second. It is easier to read than utilization: anything above 0 may be a problem. Saturation is work that waits, and waiting is time that a user feels. 80% busy can be fine or bad, depending on the resource.
3. Utilization last. It needs interpretation by resource type and by the length of the measuring window.
4. When you find something, dig in with another method, then come back and continue the loop over the remaining resources. The first thing you find may be a problem, but not the problem.

### Reading the numbers

- Utilization at 100% is usually a bottleneck. Confirm with saturation and with the effect on users. A resource can be at 100% with nobody waiting: a batch job can use every CPU, and no request waits behind it. 100% only hurts when work queues behind it.
- Utilization above 60% can already be a problem. A long window hides bursts of 100%. Some resources, like disks, cannot be interrupted mid-operation. And queueing delay grows fast as utilization rises (see [Why latency rises before 100%](#why-latency-rises-before-100)).
- Saturation above 0 can be a problem. Measure it as queue length or as time spent waiting in the queue.
- Errors above 0 are worth a look, especially when they rise while performance is bad. Errors that recover on their own (a successful retry, one failed disk in a RAID set) do not draw attention, but they still slow things down.

### Time-based and capacity-based

- Time-based: U = B/T, busy time divided by the window. This is the `%util` column in iostat.
- Capacity-based: the share of capacity in use, like memory or a connection pool at 45/50.
- 100% busy is not 100% capacity. A resource that works in parallel (an SSD, a storage array) still takes more work at 100% busy. Capacity-based is the opposite: 100% means no room left. More work must queue or fail.

### Hardware checklist on Linux

| Resource | Utilization | Saturation | Errors | Look with |
|---|---|---|---|---|
| CPU | % busy per core, not only the total | Run queue longer than the core count, PSI cpu | Rare (machine check) | `mpstat -P ALL 1`, `vmstat 1` (column r), `/proc/pressure/cpu` |
| Memory | Used against available | Swap in/out, page scanning, OOM kill, PSI memory | Failed allocations | `free -m`, `vmstat 1`, `sar -B`, `dmesg` |
| Disk I/O | `%util` | `aqu-sz`, `await` | I/O errors | `iostat -xz 1`, `dmesg`, `smartctl` |
| Disk capacity | % used | - | "No space left on device" | `df -h` |
| Network | Throughput against bandwidth | Drops, retransmits, listen backlog overflow | Interface errors | `sar -n DEV,EDEV,TCP,ETCP 1`, `nstat`, `ss` |

### Software resources

The classic examples are mutexes, thread pools, process and thread limits, and file descriptors. There are many more, and in modern systems the bottleneck often sits here.

| Resource | Utilization | Saturation | Errors |
|---|---|---|---|
| Connection pool (DB, HTTP client) | Active / max | Threads waiting for a connection, wait time | Timeout while getting a connection |
| Thread pool, worker pool | Busy / max | Queue length | Rejected tasks |
| Mutex, lock | Time the lock is held | Threads waiting, lock wait time | Deadlock, lock timeout |
| File descriptors | Open / limit | - | "Too many open files" |
| Processes, threads | In use / limit | Waiting to be created | "Cannot fork" |
| Event loop (Node.js, Netty) | % time busy | Event loop lag | - |
| Semaphore, bulkhead, rate limiter | Permits in use / max | Requests waiting for a permit | Rejected requests (429) |

If a resource does not fit these three metrics, use another method, such as latency analysis.

### Pitfalls

- A per-minute average hides bursts. CPU at 60% on average can be 30 seconds at 100% and 30 seconds at 20%. During the first 30 seconds, requests queued.
- Total CPU hides one core at 100%. A total of 12% with one core at 100% is a single-threaded app: the load is flat, the architecture is the limit.
- Looking only at utilization. Utilization says how busy a resource is. Saturation says who waits for it, and the wait is what adds latency.
- High memory "used" is not a problem by itself. Linux uses free memory as page cache. Look at available memory and at saturation (swap, OOM, PSI).
- A resource with no metric still goes on the list. Buses, interconnects and cloud quotas rarely have a dashboard. Write them down as known unknowns; do not skip them.

## RED at many layers

A request passes through many layers. Each layer has its own idea of what a "request" is. It sees only one part of the story. So R, E and D differ between layers. That difference is the clue: the problem usually sits at the first layer where the numbers start to drift. Each layer contains the layers below it. Say the lower layer is normal and the upper one is not. Then the extra time or errors come from between the two.

Read the layers top to bottom. Each one is a measuring point that sits inside the one above it. The difference between two adjacent rows tells you where the time goes.

### What each layer sees

| Layer | A "request" here | It sees | It does not see |
|---|---|---|---|
| Client (browser, mobile app) | A user action, an API call from the app | The whole time: DNS, TCP/TLS, network, server, payload download, render. Network errors and client-side timeouts | What the server does inside |
| CDN, edge | An HTTP request to the edge | Cache hit/miss, errors from the origin, latency to the origin | Errors before the edge (DNS), render time |
| Load balancer, API gateway | An HTTP request | Time from the LB until the backend answers. Errors the LB creates itself (502/503/504) | The network between client and LB, what happens inside the app |
| Service (entry span) | A request the app accepted | Processing time inside the app, errors the app knows about | Time waiting before the app accepted the request, errors at the LB |
| Downstream call, seen from the app | A call to a DB, a cache, another service | The call time, including network and maybe the wait for a connection | Execution time on the other side |
| Downstream, seen by itself | The query or request the DB or service received | The real execution time | Network, the pool wait on the client side |

### Why Rate differs

- Caches and CDNs absorb traffic. The edge receives 10k rps but the origin only 1k rps. A lower cache hit rate means the origin rate jumps, even though the user count did not change.
- Retries. A client SDK, a service mesh or the LB retries, so a lower layer sees a higher rate than the layer above. A retry storm shows up here.
- Fan-out. One request into the gateway becomes 5 internal calls, then 20 DB queries. A higher DB rate can come from new code that fans out more (N+1 queries), not from more users.
- Health checks, bots, synthetic tests. The LB counts them, but APM usually filters them out. In this lab, the RED filter skips `/health`, `/metrics` and `/admin`.
- Async. A request is accepted and pushed into a queue. The consumer's rate can differ from the producer's rate for a while.

### Why Errors differ

- Errors that never reach the server: DNS failure, TLS failure, lost network, a client-side timeout. Only the client sees them.
- The LB creates errors itself. When no backend is healthy, the LB returns 502/503/504. The app logs nothing because the request never arrived. APM reports an error rate of 0 while users fail in bulk.
- Retries hide errors. The service calls the DB, fails, retries and succeeds. The DB sees an error, the service sees a success, and only latency goes up.
- Swallowed errors. A 200 with an error in the body, or a fallback response. The layer above counts it as a success.
- Mismatched timeouts between layers. The client times out after 5 s. The server finishes at 7 s and records a 200. The client sees an error, the server sees a success and wasted the work.

### Why Duration differs

- Client duration covers everything. The server only sees the middle part.
- App duration starts when the app accepts the request. The time the request waited before that (the LB queue, the accept queue, the worker queue) is not counted. When the service is overloaded, that wait grows and APM does not see it.
- The gap between LB latency and app latency is about equal to the queueing time. The LB starts its timer before the queue, and the app starts its timer after it. The network hop between them is short, so most of the gap is the wait. That is USE saturation showing up as RED duration. How: draw the LB p99 and the app p99 on one chart. The gap is only an estimate. The slowest requests at the two layers are not always the same ones.
- A DB span seen from the app can include the wait for a connection. A 200 ms span while the DB spent 5 ms on the query: the other 195 ms were pool wait or network.

### Tail amplification

A request that calls N downstream services in parallel must wait for the slowest one. So the tail latency of the lower layer is amplified at the upper layer. Let p be the share of fast calls. With N calls, the share of upstream requests that hit at least one slow call is 1 minus p^N. Take 20 parallel calls, each above its p99 1% of the time. Then about 18% of upstream requests hit a slow call. So with a large fan-out, watch p99.9 at the lower layer, not only p99 or p50. Why: take 20 calls again. For the upstream p99 to stay fast, each call must be fast 99.95% of the time (0.9995^20 is about 0.99). So the upstream p99 depends on the lower layer's p99.95. p99.9 is the closest percentile most dashboards have.

### Compare two adjacent layers

| Observation | Likely meaning | Next step |
|---|---|---|
| Client latency up, LB latency flat | Network, CDN, DNS, a bigger payload, or slow client rendering | Split client latency into DNS, TLS, TTFB, download. Compare by ISP, region, app version |
| LB latency up, app latency flat | Requests queue before entering the app | USE for the worker pool, the accept queue, the instance count, CPU throttling |
| App latency up, a downstream span up by the same amount | The problem is in that downstream | RED and USE of that downstream |
| App latency up, downstreams flat | The problem is in the app itself | USE for host and runtime, then profiling |
| DB span (from the app) up, DB execution time flat | Waiting for a pool connection, or network | USE for the connection pool |
| Users report errors, APM error rate is 0 | Errors come from the LB or gateway, or before it | 5xx the LB creates itself, DNS, TLS |
| DB rate up, API rate flat | Fan-out, N+1, cache misses, retries | Workload characterization at the DB: which query, called from where |
| Client errors up, server errors flat, server latency up | The client times out before the server | Compare the timeout settings between layers |

### The async layer

- RED for a consumer: messages consumed per second, failed messages or messages sent to a DLQ, processing time per message.
- What users care about is end to end: from the moment the event is created until it is processed. That is the time in the queue plus the processing time.
- Time in the queue shows as consumer lag, which is the saturation of the consumer group. Processing time can stay flat while lag grows, because the produce rate is above the consume rate.
- Read lag in seconds, not in messages. A message count means nothing without the rate: 10k messages is 100 seconds behind at 100 msg/s, but 1 second at 10k msg/s. Users and SLAs think in time, for example an order must show up within 30 s. A lag in seconds compares directly with that deadline. An alert in seconds also stays right when traffic grows. An alert at 10k messages does not.
- How to measure it: take the age of the oldest message not processed yet, which is now minus its timestamp. If you only have the message count, divide it by the produce rate. The backlog is what arrived since the oldest message. Dividing by the consume rate gives another number: how long a new message waits before the consumer reaches it. The two match only when both rates are equal. When the consumer is stuck, the consume rate is 0 and that division breaks. The age still works then. In this lab, `kafka_consumer_lag_seconds` is that age, per partition.
- A poison message that is retried for ever blocks its whole partition. Errors stay low, because it is one message failing again and again. The lag of that partition grows in a straight line: new messages keep coming at a steady rate, and none leave.

## USE at many layers

The "CPU" an app sees is really many layers stacked: physical cores, the hypervisor, the VM, the container's cgroup, the runtime, then the thread pool inside the app. Each layer has its own limit, so it has its own utilization, saturation and errors. The bottleneck usually sits at the layer with the lowest limit. That layer often does not show up in host metrics. Host metrics measure the whole machine. A container limit or a pool size is smaller than the machine. So it fills up while the host still looks idle.

cgroup limits (CPU, memory, disk I/O, network I/O) are checked with USE exactly like physical resources.

While investigating, go through each layer and ask three questions: what is the limit here, has it been reached, is anyone waiting?

### Layers and their metrics

| Layer | Example resources | Utilization | Saturation | Errors |
|---|---|---|---|---|
| Hardware | CPU, DRAM, disk, NIC | As in the hardware checklist | Run queue, swap, disk queue, drops | I/O errors, NIC errors |
| Hypervisor, cloud instance | vCPU, instance bandwidth, volume IOPS | % vCPU, throughput against the instance type's limit | CPU steal time, out of CPU credits (burstable instances), volume at its IOPS limit or out of burst credits | Status check failures |
| OS, kernel | Sockets, conntrack table, system-wide file descriptors | Entries in use against max | PSI, listen backlog overflow | `nf_conntrack: table full`, dropped packets |
| Container, cgroup | CPU limit (CFS quota), memory limit, pids limit | Usage against the limit | CPU throttling, PSI per cgroup | OOMKilled, pids limit reached |
| Runtime | JVM heap, GC, Go scheduler, Node event loop, Python GIL | Heap used/max, % time in GC | GC pauses, event loop lag, goroutines waiting | OutOfMemoryError |
| Software resources in the app | Connection pool, thread pool, lock, semaphore | Active/max | Threads waiting, queue length, wait time | Timeouts, rejections |
| Managed services and quotas | DB max connections, third-party API rate limits, serverless concurrency, NAT gateway ports | Used/quota | Throttled or waiting requests | 429, ThrottlingException, connection refused |
| A whole service, as a resource | A microservice seen from the service that calls it | In-flight requests against capacity | Requests waiting for a connection or in that service's queue | The errors it returns |

### One layer hides another

- Host CPU at 40%, but the container is throttled. The container has a 1 CPU limit and the app runs 4 threads at once in a burst. The quota of 100 ms CPU per 100 ms period is used up in the first 25 ms, and the remaining 75 ms are a wait. p99 goes up, host CPU looks normal.
- VM CPU at 50%, but steal time is high. Another VM on the same physical host takes the CPU. This noise from other tenants is usually invisible from inside the VM; steal time is one of the few signals left.
- The OS sees the disk as not very busy, but `await` is up. The limit sits at the volume (provisioned IOPS, throughput, burst credits), not at the device the OS sees.
- The host has plenty of memory, but the pod is OOMKilled. It hit the container's memory limit, not the node's.

### Common examples

- Connection pool at 10/10, DB at 30% CPU. The bottleneck is the pool size, not the DB. The reverse also matters: scale to 50 pods with 20 connections each and you have 1000 connections, maybe above the DB's max connections.
- Outbound connections fail while CPU and network are low. Out of ephemeral ports, a full conntrack table, or a NAT gateway out of ports.

### A service as a resource

- For service A, service B is a resource. RED of B, measured at B, matches USE of "B as a resource" seen from A.
- Utilization is the number of requests in flight at B against B's capacity. Saturation is A's requests waiting for a connection to B or sitting in B's queue. Errors are the errors B returns.
- Going top down, you switch roles at every step: RED to locate the service, USE to locate the resource, and that resource may be another service.
- USE also applies to a whole microservice, when there are too many metrics to read them all.

### How to do it

1. Draw the resource stack for each service: instance type, cgroup limits, runtime, the pools and quotas it uses.
2. Write the limit of each layer (limit, quota, max) next to the metric you use. Utilization only has a meaning when you know the denominator.
3. Look for saturation first at each layer: throttling, steal, PSI, queue length, wait time.
4. A layer with no metric goes down as a known unknown, with a plan to add it.

## Linking RED and USE

Saturation in USE is the cause, duration in RED is the symptom. Little's Law and queueing theory connect the two with real numbers, so you can estimate instead of guess.

### Little's Law

L = λ × W

- L: the number of requests inside the system (in flight, concurrency).
- λ: the request rate, the R of RED.
- W: the mean time a request spends in the system, the D of RED.

Three common uses:

- Check whether a pool is big enough. A service gets 200 rps with a mean duration of 50 ms. So on average 10 requests are in progress at once. A thread pool of 8 is certainly saturated. 8 threads at 50 ms each finish at most 160 requests per second. 200 arrive, so the queue grows by 40 every second until requests time out.
- See how a slow downstream drags the upstream along. 400 queries/s, query latency goes from 5 ms to 50 ms: the connections in use at once go from 2 to 20. A pool of 10 is full, requests wait, and API latency rises further.
- Turn consumer lag into time. Lag in time is about lag in messages divided by the rate. In steady state, the produce rate and the consume rate are the same.

Little's Law uses averages for a system in steady state. Good enough for capacity estimates, not for tail latency. It says how many requests are inside on average. It does not say how the time is spread between them, so it cannot give a p99.

### Why latency rises before 100%

In a single-server model (M/M/1), response time over service time grows as 1 / (1 − utilization). The curve bends hard after about 60 to 70%. That is why utilization above 60% counts as a possible problem.

Why: requests arrive at random, so they come in bursts. At 50% busy, the server has a lot of idle time, and it clears a burst quickly. At 90% busy, it has only 10% idle time. A burst takes a long time to clear. The next burst often arrives before the queue is empty.

| Utilization | Response time / service time |
|---|---|
| 50% | 2x |
| 60% | 2.5x |
| 70% | 3.3x |
| 80% | 5x |
| 90% | 10x |
| 95% | 20x |

A system with many servers (many cores, many workers) stays flat longer, then goes vertical near 100%. With many servers, a new request usually finds one free. The queue only forms when almost all of them are busy. The main point does not change: latency rises clearly before the resource reports that it is full.

### Causal chain

The typical chain when an incident spreads:

1. One resource saturates (USE).
2. Requests queue, and the duration of the service that uses that resource rises (RED).
3. By Little's Law, in-flight rises. Pools and threads at the layer above are held longer, so the layer above saturates too.
4. Clients time out and retry. Rate rises, saturation gets worse. This loop feeds itself.
5. Finally errors rise: timeouts, empty pools, 503s.

So rate and duration rising together is not proof of load. Separate new traffic from retry traffic before you conclude. How: compare the user requests with the attempts the client sends. Or count retries at the client or the mesh. If attempts grow faster than user requests, the extra load is retries. In this lab, compare `client_attempts_total` with `client_requests_total`.

### The combined process

1. Problem statement: what is slow, since when, who is affected, what changed recently (deploy, config, traffic).
2. RED at the user-facing layer: which endpoint has higher duration or errors.
3. Look at the rate:
   - Rate flat: suspect architecture, go to step 4.
   - Rate up: workload characterization (who, why, what, how it changes over time). Separate real traffic from retries, bots and bugs. Still do step 4, because high load can expose a bottleneck that was always there.
4. Compare RED of two adjacent layers. Find the first layer where the numbers drift.
5. USE for the resource stack of that layer, from the instance and the cgroup down to the pools. Order: errors, saturation, utilization.
6. A resource is saturated: use a deeper method to learn why. CPU profiling for a CPU bottleneck, off-CPU analysis when waiting on locks or I/O.
7. No resource is saturated: suspect locks, waits on a downstream, or a new code path. Use latency analysis, the critical path of a trace, and a before/after comparison of the deploy.
8. After the fix, confirm with RED (duration and errors back to baseline) and USE (saturation back to 0).

## Combining with other methods

Yes, and you should. RED and USE are the first pass: fast, wide, and they tell you where to look. They rarely give the root cause by themselves. Going deeper is the job of the methods below.

### By question

| Question | Method |
|---|---|
| Is there a problem, and are users affected? | Problem statement, RED at the user-facing layer, SLOs |
| Load or architecture? | Rate in RED, workload characterization |
| At which layer? | Compare RED of two adjacent layers, drill-down, distributed tracing |
| Which resource is the bottleneck? | USE at many layers |
| Why is that resource saturated? | CPU profiling, off-CPU analysis, thread state analysis, latency analysis |
| How to fix it? | Performance mantras, static performance tuning, cache tuning |
| When does it saturate again? | Queueing theory, capacity planning, load tests that ramp up |

### Workload characterization

When the rate goes up, answer four questions: who causes the load (client, tenant, region, app version), why (a campaign, a crawler, a retry loop, a bug), what it looks like (which endpoints, which products, what mix), and how it changes over time (a step, a ramp, a daily cycle). Split the rate by those dimensions and compare with the baseline. This is also the method for cutting work that should not be there at all, like a crawler walking a whole catalog or an N+1 query.

### Sibling metric sets

| Set | Metrics | Relation to RED and USE |
|---|---|---|
| Four golden signals | Latency, traffic, errors, saturation | RED plus the service's saturation. Handy when you want one set per service |
| Core Web Vitals | LCP, INP, CLS | Duration at the client layer, measured as the user experiences a web page |
| SLI and SLO | The share of "good" requests, for example no error and under 300 ms | Turns R, E and D into a commitment. Error budgets and burn-rate alerts are built on RED |

### Performance mantras

When you know where it is slow, pick a fix in this order: do not do it, do it once, do less, do it later, do it when nobody waits, do it in parallel, do it cheaper. The first ones remove work, and the last ones only make the same work faster. Work you remove costs nothing and cannot break again, so try those first. The example at the end of this guide uses the first two that apply.

### Deeper methods

- Linux performance analysis in 60 seconds. The first ten commands on a host: `uptime`, `dmesg | tail`, `vmstat 1`, `mpstat -P ALL 1`, `pidstat 1`, `iostat -xz 1`, `free -m`, `sar -n DEV 1`, `sar -n TCP,ETCP 1`, `top`. It is a fast USE pass over one host.
- CPU profiling and flame graphs. When CPU is saturated, see which code path uses it.
- Off-CPU analysis. When duration is up but CPU is not: threads wait on locks, I/O or the network. It covers the software resources where saturation is hard to measure with USE.
- Thread state analysis. Split a thread's time by state: on CPU, waiting for CPU, waiting on a lock, waiting on I/O, sleeping. It tells you which tool to use next.
- Distributed tracing and the critical path. RED for every layer of one request, and the span that really decides the total time.
- Change events on the charts. Deploys, config changes, feature flags and scale events drawn over RED. Many "architecture" incidents start exactly at a deploy.
- Load tests that ramp up. Find the knee point of the service and use that capacity as the denominator for the service's utilization.

### Anti-methods

- Streetlight: only looking at the familiar dashboard, for example only CPU. The RED and USE checklists work against this.
- Random change: change a config and hope. Hard to tell why it works, and it can cause a worse incident at peak.
- Blame someone else: "it must be the network". Comparing two adjacent layers gives evidence instead of passing the work to another team.

## In this lab

incidentlab is a place to practice this process on a running system, not only to read about it. Every exercise is the same loop: a ticket, a change event or two on the charts, the dashboards and traces, and four questions.

The four questions of `make answer` and the step of the process they test:

1. Nature: load, architecture, or amplified rate. That is step 3, the rate.
2. Location: the first layer where the numbers drift. That is step 4.
3. Signal: the one comparison that proves it. That is steps 4 and 5.
4. Cause: the mechanism. That is steps 5 to 7.

Where each layer of this guide lives in the dashboards:

| Layer in the guide | In incidentlab |
|---|---|
| Client | loadgen. Dashboard `01 · RED by layer`, row 1. User requests by outcome, error rate, latency, requests by tenant, attempts vs user requests, p99 by endpoint |
| Load balancer | Envoy. Dashboard 01, row 2. 5xx at the LB against 5xx api records itself, LB p99 against api p99, healthy backends |
| Service (entry span) | api. Dashboard 01, row 3. Rate by route and status, error rate by route, p99 by route, the Little's Law panel, success vs error p99 |
| Downstream, seen from the app | Dashboard 01, row 4. DB session p99 (pool wait plus hold), queries/s, mean query time from the app, calls to pricing p99 and outcomes, cache hit ratio, new TCP connections against calls to pricing |
| Downstream, seen by itself | Dashboard 01, row 5. pricing's own rate and p99; Postgres queries/s and mean exec time, counted by Postgres |
| Async | Dashboard 01, row 6. Produce/s and consume/s, processing time, end-to-end p99, lag and retries by partition |
| Software resources | Dashboard `02 · USE by layer`, row 1. DB pool in use, max and waiting, acquire wait p99, pool timeouts, admission waiting and wait p99, inventory lock wait p99, threads waiting and utilization |
| Runtime | Dashboard 02, row 2. Event loop lag p99, process CPU in cores, RSS, restarts, CPU user against system |
| Container, cgroup | Dashboard 02, row 3. CPU usage against the limit, share of throttled periods, memory against the limit, PSI, OOM kills |
| Host | Dashboard 02, row 4. CPU by mode, load against the CPU count, PSI, memory available, steal time |
| Postgres | Dashboard 02, row 5. Connections by state against max, backends waiting on a lock, exec seconds per second, wait events by type, mean WAL sync time, checkpoints |
| Kafka consumer | Dashboard 02, row 6. Lag in seconds by partition, messages in worker memory, processing errors |
| Network | Dashboard 02, row 7. TCP retransmits, RTO timeouts and loss probes, connections opened, TIME_WAIT and established sockets, per service. `docs/network.md` explains it |
| Disk | Dashboard 02, row 8. IOPS, throughput, await, busy share and queue per host disk. `docs/disk.md` explains it |
| JVM | Dashboard 02, row 9. Heap by pool, GC pause p99 and heatmap, GC runs by cause, allocation rate, live data after GC, pinned virtual threads. `docs/runtime.md` explains them |
| Profiles | Dashboard 02, row 10, CPU flame graphs of api and pricing. Grafana Explore > Pyroscope, or http://localhost:4040, for every profile type. `docs/profiling.md` explains it |
| Traces | Grafana, Explore > Tempo. A dot on a latency panel is an exemplar: click it to open one real request |
| Change events | The orange lines are annotations. Some are real deploys, some are decoys, and a ticket that says "nobody deployed" can be wrong |

PSI needs a kernel that has it turned on. The OrbStack kernel has it off, so the PSI panels read 0 there. Run `kernel-check` in `make shell` to see what your VM kernel supports.

Two habits the lab rewards:

- Set the time range to 15 minutes or less and compare with the minutes before the ticket. The baseline is on the same chart.
- Write one line about the evidence you used when `make answer` asks. `make history` shows it next to the score, so you can see later which signals you trust and which you skip.

## Applying on Datadog

Each layer of RED and USE has its own data source in Datadog. A good dashboard for one service puts RED along the request path at the top and USE by layer below it, read top to bottom. The metric names below are common examples; check them in Metrics Explorer, because they depend on the integration and the agent version. incidentlab has an optional profile (`make up-datadog`) that sends traces to a Datadog Agent instead of Tempo.

### Data sources by layer

| Layer | RED from | USE from |
|---|---|---|
| Client | RUM (web, mobile): view load time, errors, Core Web Vitals. Synthetics for a stable baseline | The user's device, usually not measured |
| CDN, load balancer | CloudFront and ALB integrations: `aws.applicationelb.request_count`, `httpcode_elb_5xx` (errors the LB creates), `httpcode_target_5xx`, `target_response_time` | Healthy host count, rejected connections |
| Service | The service page in APM. Trace metrics `trace.<span>.hits`, `.errors`, and the distribution `trace.<span>` for latency (`.duration` is the old one) | Runtime metrics (JVM, Go, Node...), process, container |
| Downstream, seen from the app | The db, http and cache spans in the trace. Service Map | Connection pool metrics (emitted by the app, or through the runtime or an integration) |
| Downstream, seen by itself | Database Monitoring (query metrics). Postgres, MySQL, Redis integrations | Connections, locks, replication lag, wait events in DBM |
| Async | Data Streams Monitoring (end-to-end latency per pathway). `kafka.consumer_lag` | The broker's integration |
| Network between services | Network Monitoring: traffic and latency per service pair | Retransmits, drops |
| Host, container | - | `system.cpu.*` (including `system.cpu.stolen`), `system.load.norm.1`, `system.mem.*`, `system.io.await`, `system.io.avg_q_sz`, `system.net.*`. Containers and Kubernetes: usage against limits, throttling, OOM |
| A service without tracing | Universal Service Monitoring (eBPF) for basic RED | - |

### Common misreadings

- Latency on the service page is the entry span's. It does not include the time a request waited before entering the app. Compare with the ALB's `target_response_time` to see that wait.
- Trace metrics are computed over all traffic, independent of ingestion sampling (unless sampling happens in the app itself, for example in the OpenTelemetry SDK). The RED numbers from APM can be trusted; the list of traces you can open is only the kept part.
- "Error" in APM depends on the tracer. By default an HTTP server usually counts only 5xx; a 200 with an error in the body is not counted unless the app marks the span.
- `system.cpu.*` belongs to the host. For a container, look at CPU usage against the container's limit and at throttling.
- p99 across many hosts needs a distribution metric. A distribution computes the percentile over all values; the average of each host's p99 is wrong.

### Dashboard layout for one service

1. User layer: error rate and p75 load time from RUM, Synthetics results.
2. Load balancer: request count; 5xx from the LB separate from 5xx from the targets; `target_response_time` p99.
3. Service (APM): hits, error rate, p50/p95/p99 by endpoint. Deploy markers by the version tag.
4. Downstream: duration and errors of the db, http and cache spans by resource. Query latency from DBM next to it, to compare.
5. USE for the runtime and software resources: thread pool, connection pool (active/max and wait time), GC, event loop lag.
6. USE for the container and the host: CPU usage/limit, throttling, memory/limit, OOM, steal time, `load.norm`, disk await, retransmits.

Tip: put LB latency and APM latency on the same chart. The gap between the two lines is the queueing time.

### Monitors

- Page on-call on symptoms at the user-facing layer: SLO burn rate, p99 duration, error rate. That is RED. A page wakes a person up, so it should fire only when users hurt. A cause like a pool at 90% is often harmless. Paging on it teaches people to ignore pages.
- Use USE for early warnings or tickets, not pages: pool above 80%, throttling that lasts, a disk filling up.
- The exception worth a page is saturation that predicts a certain incident: a disk that fills within 2 hours, consumer lag past the freshness deadline.
- Alert on the gap between two layers: 5xx created by the LB above 0 while APM errors look normal means the backend is not receiving requests.

## End-to-end investigation example

A made-up case to show the walk through the layers; the numbers are chosen to add up. At 20:00 an alert says p99 of `POST /checkout` went from 300 ms to 2.5 s and the error rate from 0.2% to 3%. The final conclusion: the connection pool of checkout-api is saturated because new code holds connections longer. Not the DB, not the traffic.

Context: checkout-api runs 6 pods, 900 rps in total (150 rps per pod), each pod has a pool of 20 connections to Postgres. Deploy v2.31 at 19:40 added product suggestions to the checkout step.

### Numbers by layer

| Layer | Observation | Reading |
|---|---|---|
| Client (RUM) | Error rate up, mostly timeouts | Users are really affected |
| Load balancer | Request count same as this hour last week. 5xx created by the LB: 0. p99 2.6 s | Rate flat, so suspect architecture. The backend still receives requests |
| checkout-api (APM) | p99 2.4 s, errors mostly timeouts while getting a connection | LB and APM are about equal: no queue before the app. The problem is in the app or behind it |
| Postgres span, seen from the app | p99 1.8 s. Queries went from 1,800 to 7,200 per second | Query rate 4x while the API rate is flat: fan-out grew |
| Postgres, seen by itself (DBM) | Query latency p99 15 ms, CPU 55%, 400 of 1000 connections | The DB is not the bottleneck. The gap between 1.8 s and 15 ms is wait on the app side |
| Connection pool of checkout-api | Active 20/20 since 19:45, wait time p99 1.7 s | Saturation. This is the bottleneck resource |
| Host and container | CPU 40%, no throttling, no OOM | The lower layers are ruled out |

### Steps

1. Problem statement. Slow since 19:45, every checkout user affected, a deploy at 19:40. Write it down for the later steps.
2. RED at the LB. Rate flat against the baseline, so this is not a load problem.
3. Compare LB with APM. The two numbers are about equal, so there is no queue before the app.
4. Compare the DB span from the app with DBM. 1.8 s against 15 ms. The first layer that drifts is the hop between the app and the DB, on the DB client side.
5. USE for the resource stack of that hop. Pool 20/20 with a high wait time: saturated. Host, container and DB are fine.
6. Little's Law as a check. Before the deploy each request held a connection about 30 ms: 150 × 0.03 = 4.5 connections per pod. After the deploy about 140 ms: 150 × 0.14 = 21 connections, above the pool of 20. It matches what we see.
7. Latency analysis on a trace. The critical path shows the new code running 8 N+1 queries and calling recommendation-service (about 100 ms) while keeping the transaction open. The diff of v2.31 confirms it.
8. Fix by the performance mantras. "Do less": merge the N+1 into one query. "Do it later": call recommendation-service outside the transaction, or asynchronously. Right now: roll back v2.31.
9. Confirm. RED: p99 back to about 320 ms, errors 0.2%. USE: pool active about 5/20, wait time near 0.

### Lessons from the example

- USE on the host and the DB alone shows nothing. CPU, memory and the DB are all normal. The saturated resource is a software resource inside the app.
- RED on the service alone says it is slow, but not why. Comparing two layers (the span from the app against DBM) is what gives the location.
- "The DB is slow" is the wrong conclusion everyone wants to reach. The span from the app suggests it; DBM proves the opposite.
- Raising the pool size is not the fix. A pool of 40 on 6 pods is 240 connections and the DB survives. Scale to 30 pods and it is 1,200, above the DB's max of 1,000.

## Cheat sheet

RED, for each service at each layer:

- Rate by endpoint, against a baseline. A drop is a symptom too.
- Errors with a clear definition. Separate 5xx, timeouts, errors in the body, errors the LB creates itself.
- Duration as a distribution: p50/p95/p99, successes separate from errors, and watch in-flight as well.

USE, for each resource at each layer:

- Order: errors, saturation, utilization.
- Utilization above 60% deserves attention. Saturation above 0 deserves a look. Errors above 0 deserve an investigation.
- Remember every layer: hardware, hypervisor (steal), cgroup (throttle, OOM), runtime (GC, event loop), pools and locks, cloud quotas.
- Utilization only has a meaning when you know the limit of that same layer.

Linking the two:

- L = λ × W: rate times duration gives the number of requests in progress; compare it with the pool size.
- The latency gap between two adjacent layers is where the time is lost.
- Rate flat, duration up: architecture. Rate and duration up together: maybe load, but check retries first.
- RED finds the place, USE finds the resource, the other methods find the reason.

# Methods for the root cause and for working an incident

RED and USE say where the problem is and which resource is full. They rarely say why. The methods in this file come after them. They test a cause, split a slow task into its parts, and tell you what normal looks like. The last section is about running an incident so that its answer can be trusted. The drills of `make quiz` point at its sections as `Section > Subsection`.

## Methods for the root cause

### Scientific method

Five steps: question, hypothesis, prediction, test, analysis.

| Step | What you write down | Example from the lab |
|---|---|---|
| Question | What needs an explanation | Why did `/checkout` p99 go from 300 ms to 2 s at 20:00? |
| Hypothesis | One possible cause | The DB pool of 4 is full, so checkouts wait for a connection |
| Prediction | What must be true if the hypothesis is right | If the pool is the limit, raising `pool_max` from 4 to 8 drops checkout p99 within a minute |
| Test | Read the data, or make the change | `db_pool_waiting{job="api"}` is above 0 before the change and 0 after it |
| Analysis | Did the prediction hold? | Yes: keep the hypothesis and look for a second proof. No: drop it and write down why |

Two rules make the method work:

- The prediction must be something that can fail. "The pool is involved somehow" cannot fail, so it proves nothing. "p99 drops within a minute" can fail, so it is a real test.
- One hypothesis at a time. Two at once cannot be told apart. If you raise the pool and roll back a deploy together, you do not know which one fixed it.

Tests come in two kinds. An observational test reads data that already exists: a panel, a trace, a log line. An experimental test changes something and watches. Start with the observational test. It costs nothing and does not touch the system. In the lab, the "signal" question of `make answer` is the observational test, and the "cause" is the hypothesis.

### Diagnosis cycle

A loop: hypothesis, instrumentation, data, new hypothesis. One lap takes minutes to hours. Each lap makes the question smaller.

| Lap | Hypothesis | Instrumentation | Data | New hypothesis |
|---|---|---|---|---|
| 1 | Postgres is slow | `rate(pg_query_exec_seconds_total[1m]) / rate(pg_query_calls_total[1m])` from pgobserver | Mean exec time 5 ms, unchanged | The wait is on the client side |
| 2 | Checkouts wait for a pool connection | `db_pool_waiting`, `db_pool_in_use` against `db_pool_max` | 3 waiting, 4 of 4 in use | The hold time per checkout grew |
| 3 | Something new runs inside the DB session | One `/checkout` trace in Tempo | A pricing call sits inside `db.session` | The deploy moved the call into the transaction |

The step people skip is instrumentation. When the data to test a hypothesis does not exist, add it: a metric, a log line, a span. Do not guess. A guess that turns out right teaches nothing, and a guess that turns out wrong costs an hour. In this lab, `kafka_consumer_lag_seconds` is such a metric. A message count cannot say how late the worker is, so the age of the oldest message was added.

Signs that you need a new lap:

- Two readings disagree. The DB span from the app says 200 ms, Postgres says 5 ms. The gap is the next question.
- A metric is missing. Write it down as a known unknown and add it.
- The fix did not change the number you predicted. The hypothesis was wrong, not the fix.

### Method R

R is for response time. Start from the one business task that matters, for example one checkout. Measure where its time goes, step by step. Work on the largest share first. Stop when the task meets its target.

In the lab, a trace of one `/checkout` is this split. The server span holds the pool wait, the queries, the pricing call and the Kafka produce. A made-up profile of one slow checkout:

| Step | Time | Share |
|---|---|---|
| Wait for a pool connection | 250 ms | 62% |
| Call to pricing | 80 ms | 20% |
| Queries | 40 ms | 10% |
| Everything else | 30 ms | 8% |
| Total | 400 ms | 100% |

Rules of the method:

- Measure the task, not the system. A host at 90% CPU is not slow for anyone until a task waits for it. The profile of one task says what that task waited for.
- Work on the largest share first. A step you cut by half saves half of its share, never more. Making the pricing call 4 times faster saves 60 ms. Removing the pool wait saves 250 ms.
- Stop when the task meets its target. If the target is 300 ms, the pool wait alone gets you there. The pricing call can wait for another day.
- Averages hide the task. The mean over every endpoint is not the profile of a checkout. Split by endpoint, then by step.

Method R and the layer comparison in the guide are the same idea. The guide compares two adjacent layers to find the gap. Method R goes down one trace and lists every gap.

### Baseline statistics

"High" has no meaning without a normal value. Collect the numbers while the system is healthy, so that an incident has something to compare against.

| Metric | Why keep it | PromQL in the lab |
|---|---|---|
| Rate by endpoint | A drop is a symptom too, and a rise needs a size | `sum by (route) (rate(http_server_requests_total{job="api"}[5m]))` |
| p50 and p99 by endpoint | p99 is the number the ticket will quote | `histogram_quantile(0.99, sum by (le) (rate(http_server_duration_seconds_bucket{job="api",route="/checkout"}[5m])))` |
| Error share | To tell 0.2% from 3% | `sum(rate(http_server_requests_total{job="api",status=~"5.."}[5m])) / sum(rate(http_server_requests_total{job="api"}[5m]))` |
| CPU in cores | api uses about 0.06 cores at 50 rps, so 0.5 cores is a lot | `rate(process_cpu_seconds_total{job="api"}[5m])` |
| Pool in use | About 0.8 of 4 at 15 checkouts/s, so 4 of 4 is saturation | `db_pool_in_use{job="api"}` |

Keep a week of history. Traffic repeats by day and by week, so the right baseline for Monday 9:00 is last Monday 9:00, not the hour before. In PromQL, `offset 1w` draws last week's line on the same chart. In the lab, a healthy run is about 50 rps in total: 15 checkouts/s and 35 products/s.

A baseline also catches slow drift. A p99 that grows 5% a week never fires an alert, and after 14 weeks it is twice as slow. Only a saved baseline shows that.

### Static performance tuning

Check the configuration with no load at all. Versions, limits, pool sizes, timeouts, GC flags, kernel settings, CPU and memory limits. Many incidents are a setting that was always wrong, and a load that finally reached it.

| Layer | What to read | In the lab |
|---|---|---|
| Container | CPU and memory limits, swap | `cpus: "2.0"`, `mem_limit: 512m` and `memswap_limit: 512m` for api in `docker-compose.yml` |
| JVM | GC, heap as a share of the limit, thread stack size | `-XX:+UseSerialGC`, `-XX:MaxRAMPercentage=50.0`, `-Xss512k` in `service/Dockerfile` |
| App pools | Pool size, timeout while waiting for a connection | `pool_max` default 4 in front of a Hikari pool of 24 |
| Timeouts | Client timeout against server time | A client that gives up at 1 s while the server answers in 1.2 s wastes every request |
| Postgres | `max_connections`, memory settings, missing indexes | `max_connections=200`, no index on `reviews.product_id` |
| Kafka | Partition count against consumer count | 3 partitions, so at most 3 consumers in the group do work |
| Edge | Health check thresholds, panic threshold, retries | Envoy `healthy_panic_threshold: 0` |
| Kernel | File descriptor limit, listen backlog | `ulimit -n`, `sysctl net.core.somaxconn` |

How to do it: read every limit on the resource stack and write it next to the metric that uses it. That is step 2 of "How to do it" in the guide. Then ask for each one: what happens when load reaches it? A pool of 4 at 15 checkouts/s with a 50 ms hold is fine. The same pool at 4 times the load is full.

Static tuning finds the limit before the incident. Without it, an incident finds the limit for you.

### Knowing when to stop

Tuning has no natural end, so set the end yourself. Stop when one of these is true:

| Stop when | Example |
|---|---|
| Users are inside the SLO | p99 of `/checkout` is back under 300 ms and errors under 0.5%. The next 50 ms helps nobody |
| The next bottleneck is outside your control | After the pool fix, the largest share is a third-party API. Open a ticket with them and stop tuning here |
| The next gain costs more than it is worth | Saving 20 ms needs a rewrite of the pricing client. The SLO is already met |
| You moved the bottleneck to a cheaper resource | The limit went from the DB (hard to scale) to the api CPU (one more container). That is a good place to stop |

Two mistakes at the end of an investigation:

- Going on after the SLO is met. Every change is a risk, and the best time to stop touching the system is when it works.
- Stopping before the number you predicted moved. A fix that did not change the metric is not a fix.

## Working an incident

### Problem statement

Five questions before any dashboard. The answers decide where to look first, and they take two minutes.

| Question | Why | In the lab |
|---|---|---|
| What makes you think there is a problem? | A ticket, an alert, a user complaint and a gut feeling each point at a different layer | The ticket of `make new` |
| Has this system ever worked well? | A system that was always slow needs tuning, not an incident | Yes: the minutes before the ticket, on the same chart |
| What changed recently? | Deploy, config, traffic, data size. Most incidents start at a change | The orange lines on the charts. Some are decoys, and a ticket that says nobody deployed can be wrong |
| Who is affected? | One tenant, one endpoint, one region, or everyone | `sum by (tenant) (rate(client_requests_total[1m]))` |
| What is the environment? | Versions, limits, the load right now | 50 rps, pool 4, api at 2 cores and 512 MB |

Write the answers down. The combined process in the guide starts with this step, and the later steps read from it. Take this problem statement: p99 of `/checkout` only, since 20:00, all tenants, deploy at 19:40. It already rules out half of the system.

### One change at a time

Change one thing. Measure. Keep it or revert it. Then the next change.

Why: two changes at once cannot be told apart. You raise the pool and roll back the deploy together, and p99 recovers. Now you do not know whether the deploy was the cause, so it ships again next week.

How to measure a change:

- Decide the number before the change, and the direction it must move. That is the prediction of the scientific method.
- Wait for the window. A `rate(...[1m])` needs a full minute after the change before it shows the new state.
- Compare with the baseline, not with the minute before. The minute before was the incident.
- A change that did not move the number gets reverted, even when it felt right.

In the lab the daemon owns the fault state, so a manual change to `/admin/faults` is reverted within 3 seconds. The one knob you have is `make load X=1.5`. Use it as one change: set it, watch the pool and the p99 for a minute, set it back.

### Writing it down

Keep three things: the timeline, the evidence you used, and the dead ends.

| Keep | Why |
|---|---|
| Timeline | When the symptom started, each change, each finding, each fix attempt. The post-mortem is built from it |
| Evidence | The panel, the PromQL, the trace id that proved the cause. The next person can check it instead of trusting it |
| Dead ends | What you ruled out and how. The next person skips it, and the next incident starts further along |

A short format works: time, what you saw, what you did, what happened. One line each.

In the lab, `make answer` asks for one line about the evidence you used. `make history` shows it next to the score. After ten exercises the list says which signals you trust and which you skip.

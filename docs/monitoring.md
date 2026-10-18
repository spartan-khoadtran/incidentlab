# Reading metrics without fooling yourself

How monitoring data is made, and the places where it misleads. Three parts: the types of data and how to read them, the PromQL mistakes everyone makes once, and the metrics whose name promises more than they measure. The drills of `make quiz` point at these sections as `Section > Subsection`.

## Reading monitoring data

### Counters and gauges

| Type | What it is | Read it as | In this lab |
|---|---|---|---|
| Counter | A number that only grows, and resets to 0 when the process restarts | A rate: `rate()` or `increase()` over a window. Never the raw value | `http_server_requests_total`, `kafka_consumed_total` |
| Gauge | A level that goes up and down | As is, or `avg_over_time`, `max_over_time` | `http_server_inflight`, `db_pool_in_use`, `kafka_consumer_lag_seconds` |
| Histogram | A set of counters, one per bucket, plus `_sum` and `_count` | Each bucket needs a rate, then `histogram_quantile` | `http_server_duration_seconds_bucket` |

A raw counter value is the total since the process started. 1,284,019 requests says nothing about now. A raw counter that went down means a restart, not negative traffic. `rate()` handles the reset for you.

A histogram's buckets are cumulative: `le="0.5"` counts every request at or under 0.5 s, including the ones in `le="0.1"`. `_sum` over `_count` is the mean: `rate(x_sum[$__rate_interval]) / rate(x_count[$__rate_interval])`. Each bucket, the sum and the count are counters, so each one needs the rate first.

### Summary since boot

The first report of `vmstat` and `iostat` is the average since boot. Throw it away. A host up for 10 days with a disk at 100% for the last hour shows 100 / 240 = 0.4% in that first line. The second report is the first real sample. `iostat -y` skips the first report. `mpstat` with no interval, or an interval of 0, prints only the since-boot numbers, so always give it one: `mpstat -P ALL 1`.

The same trap hides in every counter read once. `cat /proc/stat`, `cat /proc/net/dev`, `ss -s`, a `_total` metric without `rate()`: each one is a total since start. Read it twice and take the difference, or let `rate()` do it.

The load average is already an average, over 1, 5 and 15 minutes. The 1-minute figure smooths a 10-second spike. The 15-minute figure hides it.

### Daily and weekly patterns

Traffic repeats by hour and by weekday. 300 rps is high at 3 am and low at noon. So a baseline is a curve, not a number. A fixed threshold fires every morning or never.

Compare with the same hour yesterday and with the same weekday last week. Yesterday may be a holiday and last week may have had an incident, so look at both. PromQL draws last week's line on the same chart with `offset 1w`: `sum(rate(http_server_requests_total[5m] offset 1w))`. The ratio of now against last week is one number you can alert on.

Shapes tell stories. A step at a round time is a deploy, a config push or a cron job. A ramp that follows the clock is users. A ramp that does not follow the clock is a leak or a retry loop. The pattern breaks on the Monday after a holiday, at the end of the month, on a sale day. Keep last year's curve for those.

## PromQL traps

### rate needs two samples

`rate()` needs at least two samples inside the window. With one sample there is nothing to subtract, and the result is empty. This lab scrapes every 5 s, so `rate(x[5s])` holds zero or one sample most of the time, and the chart is full of gaps.

The rule: the window should hold at least four scrapes. Grafana's `$__rate_interval` does exactly that. It is the larger of `$__interval + scrape interval` and `4 × scrape interval`. Grafana takes the scrape interval from the data source settings, not from Prometheus. If that setting is wrong, the window is wrong too. This lab sets it to 5 s, the real scrape, so the window is at least 20 s. Use `$__rate_interval` in panels. In alert rules, write the window yourself and keep the 4x rule.

`rate()` also extrapolates. It computes the slope between the first and the last sample in the window, then stretches it to the window edges. A missed scrape at the edge changes the result a little, not a lot. A window that is too short makes that stretch a large share of the answer.

### rate before sum

Write `sum(rate(x[1m]))`, never `rate(sum(x)[1m:])`. `rate()` detects a counter reset by seeing the value go down. Inside a sum, one instance's reset only makes the total dip. `rate()` reads that dip as a reset of the whole sum.

Worked example: two instances each at 1,000. The sum is 2,000. Instance B restarts and reports 5. The sum is now 1,005. `rate()` of the sum sees a drop from 2,000 to 1,005. It treats that as a reset to 0 and counts an increase of 1,005 instead of 5. The chart shows a spike of about 1,000 requests that never happened. The same holds for every `_over_time` function: rate first, then aggregate.

### increase is extrapolated

`increase(x[1m])` is `rate(x[1m]) × 60`. Both stretch the slope to the window edges, so `increase()` returns non-integers for a counter that only ever grows by whole numbers. A counter that grew by 3 can show 3.2 or 2.8. That is normal.

So never alert on `increase(x[5m]) == 1`, and never add up short-window increases to get an exact count. For an exact count over a long time, use a window many times the scrape interval. The edge stretch is a fixed-size error, so it shrinks as a share of a long window.

### histogram_quantile needs le

`histogram_quantile(0.99, sum by (le) (rate(x_bucket[$__rate_interval])))`. Three parts in that order: rate each bucket, add the buckets while keeping `le`, then take the quantile once. To split by a label, keep it next to `le`: `sum by (route, le)`. Drop `le` from the `by` and the function silently ignores every series, because none has an `le` label. The panel shows no data, with no error.

The result is an estimate. Prometheus assumes the requests spread evenly inside the bucket where the quantile falls, and draws a straight line across it. So p99 is only as exact as the bucket edges around it. If the quantile falls in the highest bucket (`+Inf`), the function returns the upper edge of the second highest bucket. So a p99 that sits flat at the highest finite edge is telling you the real value is past it. In this lab that edge is 10 s.

Never average p99 across instances. Add the buckets of all instances first, with `sum by (le)`, then take the quantile. The investigation guide explains why under Duration: a percentile is a position in a sorted list, and positions do not average.

### irate against rate

`irate()` uses only the last two samples in the window. `rate()` uses the first and the last. So `irate()` shows a spike at its real height, where `rate()` spreads it over the window. With a 15 s scrape, 600 events in one scrape and then nothing shows as 40/s in `irate()` and 10/s in `rate(x[1m])`.

That makes `irate()` good to look at a fast-moving counter over a short range, and bad for everything else. In an alert, a brief change can reset the `for` clause, so the alert flaps. In a long range the window length does not matter: `irate(x[1h])` equals `irate(x[1m])` as long as both hold two samples. The Prometheus docs give the same advice. Use `irate` only to graph volatile, fast-moving counters. Use `rate` for alerts and slow-moving counters.

### Staleness

An instant query takes the newest sample that is less than 5 minutes old. That is the lookback delta (`--query.lookback-delta`). A range query does the same at every step. So a series with no new samples and no staleness marker keeps its last value for up to 5 minutes. The chart draws a flat line where there is no data.

Prometheus marks a series stale when a scrape no longer returns it, and soon after its target is removed. After the marker, the series ends at its last sample. When a scrape fails, `up` for that target is 0. So when a service on the chart goes quiet, read `up{job="api"}` first. A gap with `up` at 0 is a target Prometheus cannot reach. It is not a service with no traffic.

Two cases keep a ghost alive:

- An exporter that puts its own timestamps on samples. When such a series stops, it keeps its last value for the 5 minutes of lookback, then disappears.
- The Pushgateway. It never forgets a series and exposes the last pushed value for ever. `up` only says that the gateway is up. The job that pushed may have died an hour ago.

In both cases a flat line is not a steady value. Check `up`, then check the time of the last sample or the last push.

## Metrics that lie

### Load average on Linux

Linux counts two kinds of task in the load average. Runnable tasks (state R) run or wait for a CPU. Tasks in uninterruptible sleep (state D) usually wait on disk I/O. So a load of 8 on a 4-core host has two readings. It can be 8 tasks fighting for 4 cores, or 2 tasks running and 6 waiting on a disk. The number alone cannot tell them apart.

Confirm with `vmstat 1`: the `r` column is runnable tasks and the `b` column is tasks blocked on I/O. Or read PSI. `/proc/pressure/cpu` says how much time tasks waited for a CPU. `/proc/pressure/io` says how much time they waited on I/O. Each file gives averages over 10, 60 and 300 seconds. In this lab the host row of dashboard 02 draws `node_load1` against `count(node_cpu_seconds_total{mode="idle"})`, next to the PSI panel. node_exporter exports PSI as `node_pressure_cpu_waiting_seconds_total`, `node_pressure_io_waiting_seconds_total` and `node_pressure_memory_waiting_seconds_total`.

| Reading | Load | vmstat r | vmstat b | PSI |
|---|---|---|---|---|
| CPU saturation | Above the core count | Above the core count | Near 0 | cpu some rising |
| Disk saturation | Above the core count | Below the core count | High | io some and full rising |
| Fine | Below the core count | Below the core count | Near 0 | Flat |

### The observer effect

Monitoring costs CPU, memory and I/O, inside the system you are watching. The usual bills:

- Trace sampling at 100%. Every request builds spans and exports them, and the collector is one more service to run. This lab samples 25% at loadgen, and every other service follows the parent's decision.
- Postgres timing. `track_io_timing` asks the OS for the current time around every I/O. The Postgres docs warn that this can cost a lot on some platforms. `pg_test_timing` measures that cost on your host. `pg_stat_statements` updates a shared entry for every statement. Its `track_planning` setting is off by default, because many connections that run the same statement then fight over the same entry.
- A heavy dashboard. Twenty panels of `histogram_quantile` over thousands of series, refreshing every 5 s, on every on-call laptop during the incident. The metrics store is a resource with its own USE, and its busiest minute is the incident.
- A profiler at a high rate, or `strace`. `strace` stops the process at every system call, so a busy process can slow down a lot. Prefer a sampling profiler at a low rate.

Measure the cost once. Read the process CPU before and after you switch the thing on: `rate(process_cpu_seconds_total[$__rate_interval])` for the service, and the same for Prometheus. Write the number down next to the setting. Then it is a known cost, not a mystery regression.

### Units and labels

Most wrong readings are a unit mix-up. Write the unit next to every number you say or type: p99 250 ms, CPU 1.6 cores of 2, lag 12 s. A number without a unit in an incident channel costs the team ten minutes of questions.

| Mix-up | What happens | Check |
|---|---|---|
| ms recorded into a `_seconds` metric | The mean from `_sum` / `_count` shows 250 s for 250 ms. p99 sticks at the highest bucket edge, because every value lands in the top bucket | Prometheus convention is base units: seconds, bytes. `LabHistogram` names end in `_seconds` and record seconds |
| Per core against per host | `rate(process_cpu_seconds_total[1m])` is in cores: 1.6 means 1.6 cores, 80% of a 2-core limit. A host total at 12% can be one core at 100% on 8 cores | Ask which denominator: one core, the limit, or the host |
| A rate read as a count | `rate(x[5m])` is per second, averaged over 5 minutes. `increase(x[5m])` is the count in those 5 minutes | Read the function, not the legend |
| Bits against bytes | A NIC is sold in bits per second and counted in bytes per second. 100 MB/s on a 1 Gbit/s link is 800 Mbit/s, 80% busy | Multiply bytes by 8 |
| MB against MiB | 512 MiB is 537 MB. A limit set in MiB read against a usage in MB looks 5% off | Look at the raw bytes once |

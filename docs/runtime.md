# The runtime layer: GC, virtual threads, system time

The investigation guide puts the runtime between the container and the app's own pools. This file goes one level down for the JVM services of the lab. It covers garbage collection, virtual threads, and the CPU the kernel spends for a process. The JVM row of dashboard `02 · USE by layer` shows every metric named here.

## Garbage collection

The JVM frees memory with a garbage collector (GC). Some GC work stops every application thread at once. That stop is a pause, and every request in flight waits for it. So the GC is a resource with its own USE numbers. Time in GC is the utilization. Pauses are the saturation. `OutOfMemoryError` is the error.

### Heap and GC in this lab

Every lab service runs the Serial collector (`-XX:+UseSerialGC`). Serial does all GC work on one thread, and the application stops while it runs.

| Setting | Value in the lab | Where it comes from |
|---|---|---|
| Heap max | 256 MB for api, fixed. pricing and worker get 50% of their container limit | `-Xmx256m` for api, `-XX:MaxRAMPercentage=50` for the others |
| Heap at start | 128 MB for api. It grows when needed | `-Xms128m` for api, `-XX:InitialRAMPercentage=25` for the others |
| Memory outside the heap | About 410 MB for api: metaspace, code cache, threads, the profiler agent and JFR. So api's container limit is 768 MB | `mem_limit: 768m` |
| Young generation | 1/3 of the heap: about 85 MB for api | `NewRatio=2`, the default |
| Old generation | 2/3 of the heap: about 170 MB for api | Same |
| Young split | Eden 68 MB, two survivor spaces of about 8.5 MB | `SurvivorRatio=8`, the default |

The heap has two parts. New objects go to the young generation. Objects that stay alive long enough move to the old generation.

| Collection | Name in the metrics | What it does | Its cost grows with |
|---|---|---|---|
| Young GC | `gc="Copy"`, `action="end of minor GC"` | Copies the live objects out of Eden into a survivor space or the old generation | The number of live young objects |
| Full GC | `gc="MarkSweepCompact"`, `action="end of major GC"` | Marks every live object in the whole heap, then slides them together | The number of live objects in the whole heap |

The pool names in `jvm_memory_used_bytes{id}` are `Eden Space`, `Survivor Space` and `Tenured Gen`. Tenured Gen is the old generation.

### GC pauses and tail latency

A pause stops every request at the same moment. Most requests finish before or after it, so p50 does not move. Only the requests in flight during the pause wait, so the pause shows up in p99.

- The share of requests that arrive during pauses is about pause time divided by the time between pauses. Example: a 150 ms full GC every 15 s hits about 1% of requests.
- A pause shows in p99 only when it hits more than about 1% of requests. Rarer pauses still show in p99.9 and on a latency heatmap.
- Read pauses as a histogram, not an average. `jvm_gc_pause_seconds_bucket` uses the same buckets as the request latency. In PromQL: `histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket{job="api"}[5m])))`.
- Time in GC is pause seconds per second: `rate(jvm_gc_pause_seconds_sum[5m])`. A value of 0.02 means the app stood still 2% of the time.
- Count the pauses by collector and cause: `sum by (gc, cause) (rate(jvm_gc_pause_seconds_count[5m]))`. Many full GCs is the warning sign. Young GCs are normal.
- A pause hits every route at the same moments. A slow downstream slows only the routes that call it.
- Read the pause at the LB, not in the app. api starts its timer when a request reaches its filter. During a pause no thread reads the socket, so a new request waits there and api never counts that wait.
- Only the one or two requests already inside api count the pause. So api's own p99 stays flat, while p99.9 at the LB jumps to about the pause length.

### Allocation rate and live data

Three numbers explain most GC trouble.

| Number | Metric | What it tells you |
|---|---|---|
| Allocation rate | `rate(jvm_gc_memory_allocated_bytes_total[5m])` | How fast Eden fills, so how often young GCs come |
| Promotion rate | `rate(jvm_gc_memory_promoted_bytes_total[5m])` | How fast the old generation fills, so how often full GCs come |
| Live data after GC | `jvm_gc_live_data_size_bytes` | What a full GC could not free. Compare it with the old generation max, `jvm_gc_max_data_size_bytes` |

How long an object lives decides where it costs:

- Short-lived objects die in Eden. A young GC never copies them, so they are almost free.
- Long-lived objects move to the old generation once and stay there. They cost marking work in every full GC.
- Medium-lived objects are the expensive ones. They survive a few young GCs, move to the old generation, then die there. They fill the old generation with garbage, and only a full GC can free it.

Time between full GCs is about the free room in the old generation divided by the promotion rate. Example: old max 170 MB, live data 140 MB, promotion 1.5 MB/s. That gives (170 − 140) / 1.5 = 20 s.

In PromQL, the share of the old generation that stays live is `jvm_gc_live_data_size_bytes / jvm_gc_max_data_size_bytes`. Dividing by `jvm_memory_max_bytes{id="Tenured Gen"}` returns nothing, because that series has the extra labels `area` and `id`.

Read live data after GC over time:

| Shape | Meaning |
|---|---|
| Low and flat | Healthy |
| High and flat, close to the max | The heap is too small for its live set. Each full GC frees little, so they come often |
| Climbing after every GC | A leak. It ends in an `OutOfMemoryError` or a container OOM kill |

With the Serial collector, `jvm_gc_live_data_size_bytes` changes only after a full GC. A service that never ran a full GC shows 0.

## Virtual threads

The lab's Micronaut services run each blocking request on a virtual thread (JDK 21). A virtual thread is cheap: thousands can wait at once. But it only runs while it sits on a platform thread, called its carrier.

### Carriers and parallelism

| Term | Meaning |
|---|---|
| Carrier | A platform (OS) thread that runs virtual threads, one at a time |
| Scheduler | A `ForkJoinPool` that hands virtual threads to carriers |
| Parallelism | The number of carriers. By default, the number of available processors |
| Mount, unmount | A virtual thread mounts on a carrier to run. It unmounts when it blocks, so the carrier can run another one |

- api has 2 cores, so the JVM sees 2 processors and the scheduler has 2 carriers.
- `-Djdk.virtualThreadScheduler.parallelism` changes the number of carriers.
- When a virtual thread blocks on a socket, a lock from `java.util.concurrent`, or a sleep, it unmounts. Thousands of requests can wait on 2 carriers. Only CPU work keeps a carrier busy.

### Pinning

A pinned virtual thread cannot unmount. On JDK 21 that happens in two cases:

- It runs inside a `synchronized` block or method.
- It runs a native method or a foreign function.

If a pinned virtual thread blocks (I/O, a sleep, a queue), its carrier blocks too. The scheduler does not add carriers to make up for pinning. A lab test shows the effect: 20 virtual threads sleep 100 ms each on 2 carriers. Outside `synchronized` they all finish in about 0.1 s. Inside `synchronized` they run two at a time and take about 1 s.

So the number of carriers becomes a hard limit. Take C carriers and H ms pinned per request. At most C × 1000 / H requests per second get through. Example: 2 carriers and 200 ms pinned is at most 10 per second.

| Fact | Detail |
|---|---|
| JFR event | `jdk.VirtualThreadPinned`: a virtual thread parked while pinned. Default threshold 20 ms. The lab lowers it to 1 ms |
| Stack traces | `-Djdk.tracePinnedThreads=full` or `short` prints a stack when a thread blocks while pinned (JDK 21 to 23) |
| Fix on JDK 21 | Replace `synchronized` around blocking work with `ReentrantLock`, or move the blocking call out of the block |
| JDK 24 and later | `synchronized` no longer pins (JEP 491). Pinning stays for native frames, class loading and class initializers |

### Reading the signals

Pinning looks like a saturated resource with no gauge of its own.

| Signal | Pinning | A saturated DB pool | CPU saturation |
|---|---|---|---|
| CPU | Low | Low | High |
| In-flight requests | Above normal. Growing without end only when demand passes the carriers | High, growing | High |
| Pool in use | Low | At max | Any |
| `jvm_virtual_thread_pinned_seconds_total` rate | A large share of the carrier count | About 0 | About 0 |

- `rate(jvm_virtual_thread_pinned_seconds_total[1m])` is seconds pinned per second. That is about how many carriers are blocked at once. When it reaches the carrier count, every other request waits.
- `rate(jvm_virtual_thread_pinned_total[1m])` is pinned parks per second. A step at a deploy points at new code.
- Demand against capacity: rate × pinned time / carriers. Below 1, the carriers act like a busy queue with C servers. Every request waits for a carrier, /health and the metrics scrape too, so p99 rises on every route.
- With 2 carriers at 75% busy, about 2 × 0.75² / 1.75 = 64% of requests find both busy and wait. Above 1, the queue grows until clients time out and health checks fail.
- A lock profile shows nothing when each request locks its own object. Nobody waits for the lock. They wait for a carrier.

## System time

CPU time comes in two kinds. Reading the split tells you whether the process works in its own code or asks the kernel for work.

### User and system CPU

| Mode | Who runs | Typical work |
|---|---|---|
| User | The process's own code | Business logic, JSON, formatting, GC |
| System | The kernel, on behalf of the process | Syscalls: reads and writes on sockets, pipes and files. Page faults. The network stack |

- In this lab, `cgroup_cpu_mode_seconds_total{mode="user"|"system"}` comes from the container's `cpu.stat` (`user_usec`, `system_usec`). cgroup v2 always reports these two, even with the cpu controller off.
- `process_cpu_seconds_total` is user plus system together. It cannot show the split.
- System share: `rate(cgroup_cpu_mode_seconds_total{mode="system"}[1m]) / ignoring(mode) rate(cgroup_cpu_usage_seconds_total[1m])`.
- A rise in system time with flat traffic means more syscalls per request. Look for small writes, lots of logging, or many short connections.
- On a host, `pidstat -u 1` shows the split per process (`%usr`, `%system`). `top` shows it for the whole host (`us`, `sy`).

### Logging costs

A log line looks free in the code. It is not.

- Logback's console appender flushes after every line by default (`immediateFlush` is true). So every line is its own `write` syscall to stdout.
- In a container, stdout is a pipe. Docker reads it and stores the log on disk.
- Every write happens under the appender's lock. Threads that log at the same time wait in line.
- The cost grows with lines per request. 500 lines per request at 50 requests/s is 25,000 syscalls per second.
- Fixes, in order: log less (put the level back, sample), then log in batches. Logback's `AsyncAppender` puts events in a queue (256 by default) and one background thread writes them. With the defaults, it drops TRACE, DEBUG and INFO events when the queue is nearly full. A caller blocks when the queue is full.
- Log volume is also disk. Rotate the files: the lab caps api's Docker log at 3 files of 20 MB.

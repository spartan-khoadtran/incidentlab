# Profiling: which code uses the time

RED finds the service. USE finds the resource. When the resource is CPU inside one process, a profile names the code that uses it. The incident exercises and the drills (`make quiz`) point at the sections here, written as `Section > Subsection`.

## CPU profiling

A CPU profile answers one question: which code paths use the CPU. It is the next step after USE shows that a process is CPU bound. Metrics say how much CPU. Traces say which request and which span. Only a profile says which function.

### Sampling profilers

- A sampling profiler stops the program at a fixed interval and records the stack of the running thread. One stack is one sample.
- Each sample stands for one interval of CPU time. With a 10 ms interval, one sample is 10 ms of CPU.
- So the sample count gives the CPU time. Samples per second = cores used / interval. A process at 1.5 cores, sampled every 10 ms, gives about 150 samples per second.
- The cost depends on the interval, not on how often a function runs. That is why a sampling profiler can stay on in production.
- An instrumenting profiler times every call instead. Its cost grows with the call count, and the extra cost can change the result.
- A sample count is a statistic. 2 samples out of 1,000 may be noise. 400 out of 1,000 is about 40% of the CPU in that window.
- Many JVM profilers can only take a stack at a safepoint, so they favour the code near safepoints. async-profiler reads stacks with `AsyncGetCallTrace` and does not have this safepoint bias.

async-profiler, which the Pyroscope Java agent runs, has three CPU engines:

| Engine | What fires a sample | Needs | Kernel frames |
|---|---|---|---|
| `cpu` | A perf_events counter per thread | `perf_event_open`, which Docker's default seccomp profile blocks | Yes |
| `itimer` | `setitimer(ITIMER_PROF)`: a signal per interval of CPU time used by the whole process | Nothing special | No |
| `ctimer` | CPU timers made with `timer_create` | Nothing special | No |

`itimer` has limits. Signals are not spread evenly between running threads. The resolution is one kernel tick: 10 ms at `HZ=100`, 4 ms at `HZ=250`. Its main advantage is that it works in any container.

### Reading a flame graph

- A flame graph merges all samples into one picture. Each box is a function. A box sits next to the box of the function that called it.
- Width is the share of samples where the function was on the stack: its own work plus everything it called.
- Depth is only the length of the call chain. A deep stack is not a slow stack.
- The x-axis is not time. Boxes are placed so equal stacks merge. A 10-second spike and a steady cost can look the same.
- Self time counts the samples where the function is the last frame, running its own code. Total time counts the samples where it is anywhere on the stack.
- Grafana and Pyroscope draw the root at the top, with each callee below its caller. The classic flame graph puts the root at the bottom. The reading is the same.
- Next to the graph there is a table. Sort it by Self to find the hot function. Sort it by Total to find the caller that owns the work.
- Compare with a baseline. A wide box is only news if it was narrower before the incident.

| Shape | Reading |
|---|---|
| A wide box with no children | A hot function: its own code uses the CPU |
| A wide box with many narrow children | An entry point: the work is in its callees, so look one level down |
| A new wide tower that was not there before | A new code path, often from the latest deploy |
| Many thin towers, none wide | No single hot spot. The fix is less work overall, or more CPU |
| Wide JVM frames (GC, the JIT compiler) | Runtime work, not app code: allocation pressure, or warm-up after a start |

### From a trace to a profile

- A trace shows one request: the services and spans it crossed, and the time of each one.
- A trace only sees code that has a span. When a span is long and no child span explains it, the time is in the service's own code. The trace cannot say which code.
- A profile shows which code, but for all requests at once. It does not know which request a sample came from.
- So use both. The trace gives the service and the time range. The profile of that service, over that time range, gives the code.
- Span profiles join the two signals. A bridge library labels each sample with the span that was running. Then Grafana can show the flame graph of one span. Grafana needs the `pyroscope.profile.id` span attribute for this, and the bridge sets it.
- This lab does not install the bridge. Link by hand: the same `service_name`, the same time range.
- A profile is a sum over many requests. A rare slow request barely moves it. Use traces for the tail. Use profiles to see where the CPU goes in general.

## Off-CPU profiling

A CPU profile only sees threads that run. A request that waits for a lock, a socket or a pool uses no CPU while it waits. So the CPU profile does not show that time. Off-CPU analysis covers it.

### Wall-clock and off-CPU time

- Wall-clock time is time on CPU plus time off CPU.
- Off CPU means waiting. The thread waits for a lock, for disk or network I/O, for a sleep, or for a free core.
- Example: a request takes 200 ms and uses 5 ms of CPU. The CPU profile holds only those 5 ms. The other 195 ms are off CPU.
- A wall-clock profiler samples every thread at an interval, running or not. A waiting thread still gets samples, with the stack of the wait. async-profiler calls this `wall` mode.
- Idle threads get samples too. A pool thread parked while it waits for work fills a wall profile with noise. Filter by thread name, or read only the request threads.
- Rule of thumb: duration up and CPU up, take a CPU profile. Duration up and CPU flat, use traces and a wall-clock or lock profile.

| Where the thread is | In a CPU profile | In a wall-clock profile |
|---|---|---|
| Running code | Yes | Yes |
| Waiting to enter a lock | No | Yes |
| Waiting on a socket (DB, HTTP) | No | Yes |
| Ready to run, waiting for a free core | No | Yes |
| Idle, parked until work arrives | No | Yes, as noise |

### Lock profiling

- A lock profile records where threads wait to enter a lock, and for how long.
- async-profiler's `lock` mode covers Java monitors (`synchronized`) and `java.util.concurrent` locks like `ReentrantLock`. It writes the JFR events `jdk.JavaMonitorEnter` and `jdk.ThreadPark`.
- `lock=10ms` is a sampling interval, not a filter. The profiler takes a sample each time the total lock wait grows by 10 ms. So the profile follows total wait time, the way a CPU profile follows CPU time.
- The top frame of a lock sample is the class of the lock. The frames under it are the code that waited.
- In Pyroscope, monitor waits are the `mutex` profile type and parks are `block`. Each has a contentions count and a delay in nanoseconds.
- A lock in another process is not a Java lock. A Postgres row lock makes the thread wait for the DB to answer, on the network. The JVM lock profile does not show it, so read `pg_lock_waiters` instead.

### What a profile cannot show

- Which request was slow. Samples from all requests are merged, unless you have span profiles.
- When, inside the time range. The x-axis is not time. Narrow the time range to find a short spike.
- Work in another process. A profile of api does not show Postgres running a query, or Envoy queueing a request. Profile the process that does the work.
- How many calls. A cheap function called a million times and a slow function called once can have the same width. Metrics or traces give the call count.
- Kernel frames, with `itimer`. Time in a system call shows under the Java or native frame that made the call.
- Memory outside the Java heap. The allocation profile samples Java heap allocations only. Memory taken with `Unsafe` or by native code is not in it. async-profiler's `nativemem` mode covers that, and this lab does not turn it on.
- Waiting, in a CPU profile. See [Wall-clock and off-CPU time](#wall-clock-and-off-cpu-time).

## Profiles in this lab

### How the agent is wired

- Pyroscope 1.21.1 stores the profiles. Grafana reads them through the Pyroscope datasource (uid `pyroscope`).
- api, pricing and worker load the Pyroscope Java agent 2.9.2 at start, through `JAVA_TOOL_OPTIONS`. The agent runs async-profiler inside the JVM and uploads a JFR file every 10 seconds.
- `service_name` in Pyroscope is the compose service name: `api`, `pricing` or `worker`.
- The agent uses `itimer` because Docker's default seccomp profile blocks `perf_event_open`. The price: no kernel frames, and samples are not spread perfectly evenly between threads.
- Request code in api and pricing runs on virtual threads. Virtual threads run on a few carrier threads, by default one per available core: 2 for a 2-core limit. Thread dumps name the carriers `ForkJoinPool-N-worker-M`. `top -H` cuts a thread name to 15 characters, so there they all show as `ForkJoinPool-N-`.

| Setting | Value | Meaning |
|---|---|---|
| `PYROSCOPE_PROFILER_EVENT` | `itimer` | One CPU sample per 10 ms of CPU the process uses (10 ms is the agent's default interval) |
| `PYROSCOPE_PROFILER_ALLOC` | `512k` | One allocation sample per 512 KB allocated, on average |
| `PYROSCOPE_PROFILER_LOCK` | `10ms` | One lock sample per 10 ms of total lock wait |
| `PYROSCOPE_FORMAT` | `jfr` | JFR is the only output that carries CPU, allocation and lock events together |
| `PYROSCOPE_UPLOAD_INTERVAL` | `10s` | A profile arrives every 10 seconds, so the last few seconds are always missing |

The profile types you see in Grafana:

| Profile type | Question it answers |
|---|---|
| `process_cpu` > `cpu` | Which code uses the CPU? |
| `memory` > `alloc_in_new_tlab_bytes` | Which code allocates the most on the Java heap? |
| `mutex` > `delay` | Where do threads wait on Java monitors? |
| `block` > `delay` | Where do threads wait on `java.util.concurrent` locks? |

### A good first query

1. In Grafana, open Explore and pick the Pyroscope datasource.
2. Set the profile type to `process_cpu` > `cpu` and the label selector to `{service_name="api"}`.
3. Set the time range to the last 15 minutes, or to the incident window.
4. Sort the table by Self, then read the flame graph from the widest boxes down.
5. Click Split. Set the other pane to a window before the start, and compare the widest boxes.

- A healthy api uses a small part of one core at 50 rps. Its profile is framework, JSON and JDBC code, with no single wide box.
- The same query works for `{service_name="pricing"}` and `{service_name="worker"}`.
- Pyroscope's own UI at `http://localhost:4040` shows the same data.
- Without Grafana, `top -H` shows CPU per thread: `docker compose exec api sh -c 'top -H -b -n 1 -p "$(pgrep -o java)" | head -20'`. One thread near 100% is a single-threaded bottleneck. The same CPU spread over the carriers is CPU work on the request threads.

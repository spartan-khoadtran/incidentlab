# CPU: shares, credits, busy loops and waiting

Four CPU situations the dashboards get wrong at first sight. Two are about a limit that moves or hides: shares and credits. Two are about CPU that lies: a core at 100% with no work behind it, and a slow request that uses no CPU at all. Each section ends with where to look in this lab.

## CPU shares and quotas

A container has two kinds of CPU setting. A quota is a hard ceiling. `cpus: "2.0"` in compose means at most 2 cores, checked every 100 ms period. A share, which cgroup v2 calls a weight, is a priority. It only decides who wins when the host runs out of CPU.

| Setting | cgroup v2 file | What it does | When it matters |
|---|---|---|---|
| Quota (`cpus`) | `cpu.max`, written as `quota period` in microseconds, default `max 100000` | Caps the CPU time per period. Over the cap, the tasks wait for the next period. That wait is throttling | Always, even on an idle host |
| Weight (`cpu_shares`) | `cpu.weight`, 1 to 10000, default 100 | Splits the CPU between cgroups that all want to run, in proportion to their weights | Only under contention |

### Shares only matter under contention

- An idle neighbour changes nothing. A container with a huge weight that sleeps gives nothing and takes nothing.
- Two containers that both want everything split the CPU by weight. Weights 300 and 100 give 75% and 25%. Weights 100 and 100 give half each.
- Three containers that all want everything split by the sum of the weights. Weights 3000, 100 and 100 give 93.75%, 3.125% and 3.125%.
- A container that wants less than its share keeps only what it uses. The rest goes to the others. Nobody is held back while a core sits idle.
- Docker's `cpu_shares` is the old cgroup v1 number, default 1024. runc turns it into a `cpu.weight`. runc 1.3.2 and later use a curve that maps 1024 to 100 and 65536 to about 3000. Older runc used a straight line, `1 + (shares − 2) × 9999 / 262142`, which maps 1024 to 39 and 65536 to 2500. Read the real value inside the container: `cat /sys/fs/cgroup/cpu.weight`.
- A weight has no utilization of its own, so USE looks elsewhere. Saturation shows at the host: load above the CPU count, PSI cpu. It also shows inside the losing containers: their PSI cpu rises, their CPU usage drops, their latency rises. Their throttled share stays at 0: a weight is not a quota.
- A container with no `cpu_shares` gets no conversion. It keeps the cgroup v2 default weight of 100.
- In this lab, `noisy` has `cpu_shares: 65536` and every other container keeps weight 100. When noisy burns CPU it wins almost all of a contended core, so every service slows down at once. When noisy idles, the setting is invisible. A fork storm from the same container uses little CPU, so its weight never comes into play.

### CPU credits on burstable instances

- A burstable VM (the AWS T family, for example) has a baseline: a share of each vCPU it may use all the time. On AWS it is 10% for a t3.micro, 20% for a t3.small, 30% for a t3.large.
- Below the baseline the VM earns credits. Above it, it spends them. One credit is one vCPU at 100% for one minute. A t3.small earns 24 credits an hour and can bank 576.
- Credits earned per hour = baseline × vCPUs × 60 minutes. Credits spent per minute = vCPUs × utilization.
- When the balance reaches zero in standard mode, the VM falls to its baseline and stays there until it earns credits again. Nothing in the VM changed: same code, same load, less CPU.
- Unlimited mode keeps bursting after the balance is gone and bills the extra CPU. On AWS, T3 instances start in unlimited mode by default. A team that picks standard mode to cap the bill gets the drop instead.
- How to see it. The credit balance metric (`CPUCreditBalance` on AWS, 5-minute resolution) runs down to zero before the slowdown. Inside the VM, steal time rises. Steal is time a vCPU wanted to run while the hypervisor ran something else. It is the `steal` column of `/proc/stat`, `st` in `top` and `%steal` in `mpstat`. CPU utilization seen from inside may even fall, because the guest cannot use what it does not get.
- How long until it runs out: balance / (spend − earn) per minute. A t3.small (2 vCPUs, 20% baseline) at 100% on both vCPUs spends 2 credits a minute and earns 0.4. A full bank of 576 lasts 576 / 1.6 = 360 minutes.
- How this lab models it. The lab has no hypervisor, so the exercise lowers api's CPU quota after a few minutes instead. You see `cgroup_cpu_limit_cores` drop and the throttled share climb. On a real burstable instance the quota does not move. Steal time and the credit balance are the signals.

## CPU busy with no work

A core at 100% is utilization. It is only a problem when work waits behind it. Two cases look like a busy service and are not.

### Busy polling

- A consumer loop asks the broker for messages. A blocking poll with a timeout sleeps until data arrives or the timeout passes. A poll with a zero timeout returns at once, empty when nothing is buffered, and the loop calls it again. The thread never sleeps.
- Signature: process CPU at one core, because one thread cannot use more. If the container's limit is one core, it is at its limit and throttled. Meanwhile the consume rate is unchanged, processing time per message is flat and lag stays at 0. Utilization without saturation.
- RED and USE together tell it apart from a real overload. An overloaded consumer has lag growing (saturation) and a consume rate pinned at capacity. A spinning consumer has neither.
- Adding consumers makes it worse: every new one spins too.
- Fix: a blocking poll with a timeout (a few hundred milliseconds is usual), a wait on a condition, or a short sleep when a poll returns empty. The same bug exists outside Kafka: a loop that checks a flag with no sleep, a `select` with a zero timeout, a retry loop with no backoff.
- In this lab: `rate(process_cpu_seconds_total{job="worker"}[1m])` against `sum(kafka_consumer_lag_messages)` and `sum(rate(kafka_consumed_total[1m]))`. The worker has one core, so the throttled share rises too.

### Short-lived processes

- A process that lives a few milliseconds never appears in `top` or `ps`. Both take a snapshot, and the process is gone between two snapshots. Hundreds of such processes per second still cost CPU: the kernel builds and tears down each one, and the loader maps its binary.
- The counter that sees them is the `processes` field of `/proc/stat`, the number of processes and threads created since boot. node-exporter exposes it as `node_forks_total`. `rate(node_forks_total[1m])` is the fork rate of the whole host, containers included. Tracing tools that hook `exec`, like `execsnoop` from bcc or bpftrace, list each short process with its command line.
- Where it comes from: a cron job in a shell loop, a health check that runs a command every second, a log shipper that calls an external program per line, a build or a test suite on the host.
- Cost: fork rate × CPU per fork. At 200 forks/s and 1.5 ms of CPU each, that is 0.3 cores. It shows as host CPU with no process to blame, much of it in `system` mode.
- Is it the problem? Ask USE: is anyone waiting? Load against the CPU count, PSI cpu and the throttled share of your services. A fork storm that leaves the host under its CPU count is a lead, not a cause.
- In this lab, `noisy` runs the storm. `rate(node_forks_total[1m])` jumps, `rate(noisy_forks_total[1m])` names the container, and api's own numbers do not move because of it.

## Off-CPU time

A request is either on a CPU or waiting for something: a CPU, a lock, I/O, a downstream, a timer. When duration is up and CPU is not, the time is off-CPU. USE on the hardware finds nothing. The resource is a software one, or the wait is on another service.

### Waiting on a lock

- Signature: latency up on the routes that take the lock, flat on the others. Process CPU low, or even lower than before. Pool, downstream spans and host flat. Threads pile up inside the app.
- A lock is a resource with the three USE numbers. Utilization: the share of time it is held, `rate(held_seconds_total[1m])`. Saturation: the threads waiting, and their wait time. Errors: a lock timeout or a deadlock.
- The math is the M/M/1 queue with the lock as the single server. Response time is S / (1 − ρ), where S is the hold time and ρ the utilization. The wait alone is the response minus the service: W = S × ρ / (1 − ρ).

| Utilization of the lock | Mean wait, in hold times |
|---|---|
| 50% | 1 |
| 75% | 3 |
| 80% | 4 |
| 90% | 9 |
| 95% | 19 |

- The table assumes random hold times. A lock held for the same time on every call waits half as long: W = S × ρ / (2 × (1 − ρ)).
- In M/M/1 the time at the lock (wait plus hold) has an exponential spread with mean S / (1 − ρ). Its p99 is about 4.6 times that mean, because ln(100) ≈ 4.6.
- Little's Law gives the same picture from the other side: threads waiting ≈ arrival rate × mean wait.
- How to find the lock. A trace shows a span with no child and no CPU. A thread dump shows many threads BLOCKED or WAITING on the same monitor or `ReentrantLock`. A profiler in wall-clock mode, not CPU mode, shows the frames where the time passes.
- Fix, in the order of the performance mantras: do not take the lock on the request path (refresh in the background), do it once (one refresh per interval, not per request), do less under the lock (move the slow part outside), or shard the lock (one per key).
- In this lab: `app_lock_wait_seconds`, `app_lock_waiting` and `app_lock_held_seconds_total` for api's inventory lock, in the Software resources row of the USE dashboard. Its hold times are random, so the panels follow the table.

### Thread states

| State | What the thread does | Signal | Tool |
|---|---|---|---|
| On CPU | Runs code | Process CPU, per-thread CPU | `top -H`, `pidstat -t 1`, a CPU profiler, a flame graph |
| Runnable, waiting for a CPU | Ready, but no core is free or the quota is spent | Run queue above the core count, PSI cpu, throttled periods, steal time | `vmstat 1` column r, `/proc/pressure/cpu`, `cpu.stat` in the cgroup |
| Blocked on a lock | Waits for another thread to release something | Lock wait time, threads waiting | A thread dump (`jstack`), lock profiling, a wall-clock profiler |
| Blocked on I/O | Waits for the disk or the network | Disk: `iowait` and `procs_blocked`. Network: the client span of the downstream call | `iostat -xz 1`, traces, off-CPU analysis |
| Sleeping | A timer, or a blocking poll with a timeout | Nothing. The thread uses no CPU | A thread dump shows a sleep as TIMED_WAITING. A thread blocked in a socket read or a selector shows RUNNABLE, though it uses no CPU |

- A thread dump from `jstack` lists platform threads only. JDK 21 virtual threads show up in `jcmd <pid> Thread.dump_to_file <file>`.
- The first question for a slow request: in which state does its thread spend the time? On CPU means profile the CPU. Runnable means a CPU limit or a noisy neighbour. Blocked on a lock means USE for the lock. Blocked on I/O means RED of the downstream or USE of the disk. Sleeping means a timeout or a delay in the code.
- The lab's exercises map onto these states. The single-thread CPU burn is on CPU. The CPU throttle, the noisy neighbour and the CPU credits are runnable but not running. The inventory lock is blocked on a lock. The slow downstream and the exhausted pool are blocked on I/O. The busy poll is a thread that should sleep and does not.

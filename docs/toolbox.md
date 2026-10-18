# Investigating from a shell

Dashboards are not always there. A new host has no agent yet. The metrics pipeline is down. Or the question is too narrow for any panel. Then you log in and read the machine with a few standard Linux commands.

This file covers those commands and the tools to install before you need them. It also covers the toolbox this lab gives you to practice. The RED and USE method itself is in [investigation-guide.md](investigation-guide.md). Here the same questions are asked with a shell instead of Grafana.

## The 60-second checklist

Ten commands give a first picture of a Linux machine in about a minute. They answer one question: which resource is busy, full or failing? Run them in order. Each one takes a second or loops every second until you press Ctrl-C.

The checklist is wide, not deep. It points at a resource. Then you look at that resource with more focused tools.

### The ten commands

| # | Command | What it shows | Read this |
|---|---|---|---|
| 1 | `uptime` | Load average over 1, 5 and 15 minutes | Compare with the CPU count (`nproc`). A 1-minute value far above the 15-minute one means the load is rising now. |
| 2 | `dmesg -T \| tail` | The last kernel messages | OOM kills (`Killed process`), dropped packets, disk errors. |
| 3 | `vmstat 1` | CPU, memory and run queue, every second | `r` against `nproc`, `si`/`so` (swap), `us sy id wa st`. |
| 4 | `mpstat -P ALL 1` | Each CPU on its own | One CPU at 100% `%usr` while the others idle. |
| 5 | `pidstat 1` | CPU per process | `%CPU`. 100 means one full CPU, so 250 is two and a half. |
| 6 | `iostat -xz 1` | Each disk with I/O | `r_await`, `w_await` (ms per request, queue time included), `aqu-sz`, `%util`. |
| 7 | `free -m` | Memory in MiB | `available`, not `free`. |
| 8 | `sar -n DEV 1` | Each network interface | `rxkB/s`, `txkB/s`, `%ifutil`. |
| 9 | `sar -n TCP,ETCP 1` | TCP connections and errors | `active/s` (new outbound), `passive/s` (new inbound), `retrans/s` (`retrseg/s` from sysstat 12.7.6). |
| 10 | `top` | The overview, refreshed | The `%Cpu(s)` line, then the top processes. Press `1` for each CPU and `H` for threads. |

Some details change how you read them:
- Linux load counts tasks that run or wait for a CPU. It also counts tasks in uninterruptible sleep, often disk I/O. A high load is not always a CPU problem.
- The first line of `vmstat` and the first report of `iostat` are averages since boot. Read the lines after it.
- `r` in `vmstat` counts tasks running or waiting to run. More than `nproc` for many seconds means tasks queue for a CPU.
- `st` (steal) is time the hypervisor gave your virtual CPU to someone else.
- The `free` column leaves out the page cache, so it is low on a healthy machine. `available` estimates what a new program can get without swapping.
- `%util` near 100 on one disk means it was always busy. A device that serves many requests at once, like an SSD, can still take more.

### From host to container

Containers share the kernel of the machine. A tool on the host sees every container, but only if you know where to look.

| What | Seen from the host | Where the container's own view lives |
|---|---|---|
| Processes | Every container's processes, with host PIDs | `pid api` gives the host PID of api's container |
| CPU and memory totals | `vmstat`, `free`, `/proc/meminfo` show the whole machine | The cgroup files: `cpu.max`, `cpu.stat`, `memory.current`, `memory.max` |
| Network | `sar -n DEV`, `ss` show one network namespace | Run the tool inside the container's namespace with `nsenter -t PID -n` |
| Kernel log | `dmesg` is shared by all containers | The same `dmesg`. An OOM kill names the PID and the process. |

`free` inside a container still shows the machine's memory, not the container limit. The limit is in the cgroup.

Find a process's cgroup in `/proc/PID/cgroup`. On cgroup v2 it has one line, `0::/path`. The files are under `/sys/fs/cgroup/path`:

| File | Content | Read it as |
|---|---|---|
| `cpu.max` | `MAX PERIOD` in microseconds, default `max 100000` | `8000 100000` is 0.08 cores. `max` means no limit. |
| `cpu.stat` | `usage_usec`, `nr_periods`, `nr_throttled`, `throttled_usec` | Read it twice. The share of throttled periods is the change in `nr_throttled` over the change in `nr_periods`. |
| `cpu.weight` | Share of CPU under contention, 1 to 10000, default 100 | Only matters when the CPUs are full. |
| `memory.current` | Memory charged to the cgroup now, in bytes | Compare with `memory.max`. |
| `memory.events` | Counters, among them `oom` and `oom_kill` | `oom_kill` above 0 means the kernel killed a process here. |
| `cpu.pressure`, `memory.pressure` | PSI: the share of time tasks waited for the resource | `some avg10=` is the last 10 seconds. |

After an OOM kill Docker may restart the container, and its counters can start again from zero. `dmesg` keeps the kill.

A container has its own network namespace. `nsenter -t PID -n ss -tn` runs `ss` from the toolbox inside the namespace of process PID. You get the container's connections with the host's tools, even if the container image has no `ss`.

### What the checklist cannot see

The checklist reads resources the kernel knows. Many incidents hide in places it does not reach:

| Blind spot | Why | What to use instead |
|---|---|---|
| Request latency | The kernel counts CPU and I/O, not requests | RED: the service's `/metrics` or its logs |
| Software resources | A connection pool or a lock inside the app is not a kernel object | The app's metrics (`db_pool_*`), the database views (`pg_stat_activity`, `pg_locks`) |
| Waiting threads | A thread blocked on a lock or a pool uses no CPU, so it looks idle | Thread dumps, `pg_stat_activity` wait events, off-CPU tracing |
| Code inside the JVM | `top -H` shows thread names, not methods | A profiler, or `perf` with a perf map for JIT code |
| The past | The commands show now | Metrics history. `dmesg` and cumulative counters keep a little. |

Two habits help. Take two readings of a counter some seconds apart and divide the change by the time. And write the limit next to every number you read.

## Crisis tools

During an incident you need the tools now. That is the worst moment to install them.

### Install before the incident

A host in trouble may not let you install anything. The package mirror may be down or the disk full. The memory may be too tight to run the package manager. So the tools belong in the base image of every host and every debug container.

| Package (Ubuntu) | Gives you |
|---|---|
| `procps` | `ps`, `top`, `vmstat`, `free`, `uptime`, `pgrep`, `watch` |
| `sysstat` | `mpstat`, `pidstat`, `iostat`, `sar` |
| `iproute2` | `ip`, `ss`, `nstat` |
| `util-linux` | `dmesg`, `nsenter`, `lsns` |
| `strace`, `lsof`, `tcpdump` | System calls, open files and sockets, packets |
| `bpftrace` | Kernel tracing with one-line programs |
| `linux-tools-common`, `linux-tools-generic` | `perf` |
| The clients of your stack | `psql`, `curl`, `jq` |

Two tools depend on the kernel more than the others:
- `perf` on Ubuntu runs the build that matches `uname -r`. In a container the VM kernel never matches the package. So the lab's toolbox calls the installed build directly.
- `bpftrace` needs a kernel with BPF, and root or the BPF capabilities. Tracepoints also need tracefs mounted at `/sys/kernel/tracing`. Many probes read kernel types from BTF, in `/sys/kernel/btf/vmlinux`.

`strace` stops the traced process at every system call. It can make it many times slower. Use it for seconds on a production process, not minutes. `strace -c -f -p PID` counts system calls by name until you press Ctrl-C.

### Tracing one-liners

`bpftrace` runs a small program in the kernel and prints a summary. The syntax below is for bpftrace 0.20, the version in Ubuntu 24.04. Versions before 0.19 write `args->filename` instead of `args.filename`.

| One-liner | What it shows | Needs from the kernel |
|---|---|---|
| `bpftrace -e 'tracepoint:syscalls:sys_enter_openat { printf("%s %s\n", comm, str(args.filename)); }'` | Every file opened, with the process name | Syscall tracepoints, tracefs |
| `bpftrace -e 'tracepoint:raw_syscalls:sys_enter { @[comm] = count(); }'` | System calls per process name, printed at Ctrl-C | Raw syscall tracepoints, tracefs |
| `bpftrace -e 'profile:hz:99 { @[comm] = count(); } interval:s:10 { exit(); }'` | CPU samples per thread name for 10 seconds | Perf events |
| `bpftrace -e 'tracepoint:block:block_rq_issue { @ = hist(args.bytes); }'` | A histogram of disk I/O sizes | Block tracepoints, tracefs |

`comm` is the thread name, cut to 15 characters. The JVM gives its threads their Java names. So the profile one-liner shows which Java thread burns the CPU. It samples 99 times per second, not 100, so it does not run in step with timers.

`perf top -e cpu-clock` shows the functions that use the most CPU, on every CPU, live. `cpu-clock` is a software event, so it works on a VM without hardware counters. It needs `perf_event_open`. `perf_event_paranoid` limits users without `CAP_PERFMON`, so run it as root. JIT-compiled Java code shows as raw addresses, because perf cannot find its symbols.

## The toolbox in this lab

The lab runs one more container, `toolbox`. It is a root shell on the Docker VM with every tool from the tables above.

### make toolbox and make shell

`make shell` opens the toolbox and prints a short help. `toolbox-help` prints it again.

| Setting | Effect |
|---|---|
| `pid: host` | `pgrep`, `top` and `pidstat` see the processes of every container |
| `cgroup: host`, `/sys/fs/cgroup` read-only | `/proc/PID/cgroup` paths are complete, and you can read but not change a limit |
| `privileged: true` | `dmesg`, `nsenter`, `strace`, `perf` and `bpftrace` are allowed |
| The docker socket | `pid SERVICE` prints a lab container's PID, and `containers` lists every container with its PID and cgroup |
| The compose network | `psql`, `curl http://api:8000/metrics`, `curl http://worker:8002/metrics` work by service name |

The toolbox adds a few helpers:
- `pid SERVICE` prints the host PID of a lab container's main process, from the docker socket: `pid api`, `pid pricing`, `pid worker`, `pid noisy`, `pid postgres`. For api, pricing, worker and noisy that is the java process. For postgres it is the postmaster.
- Use `pid`, not `pgrep`. With `pid: host`, `pgrep -x postgres` also matches the Postgres of any other project on the same VM.
- `cg PID` prints the cgroup directory of a process. `cat $(cg $(pid api))/cpu.max` reads api's CPU limit.
- `containers` lists the containers with their main PID.
- `kernel-check` says which kernel features work on this VM. Run it first.

On OrbStack every tool works, but the kernel has PSI turned off, so the `*.pressure` files are missing. Docker Desktop runs a different VM kernel. There `bpftrace` and `perf` may fail, and `kernel-check` says so. The checklist, the cgroup files, `psql` and `curl` work on both.

`make toolbox` starts an exercise like `make new`, with the same `LEVEL`, `TOPIC`, `ID` and `FORCE`. It only picks scenarios tagged `[toolbox]` in `make list`. The rule is CLI only: no Grafana. You answer the same four questions with `make answer` and get the same score. After the answer, the explanation lists the commands that prove the cause.

Many `/metrics` values are counters since the start of the process. One reading says little. Read twice, some seconds apart, and divide the change by the seconds between them. That is what `rate()` does in Prometheus.

The toolbox is root on the VM with the docker socket. That is fine for a lab on a laptop. Never run a container like it on a shared host.

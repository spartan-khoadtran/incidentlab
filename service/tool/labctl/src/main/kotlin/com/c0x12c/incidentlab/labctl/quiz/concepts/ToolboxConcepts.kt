package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/toolbox.md: what each shell command shows, and what it cannot show. */
internal object ToolboxConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- The 60-second checklist
    ChoiceQuestion(
      text = "vmstat 1 prints its first line, then one line per second. The first line shows id 95. The next ones show id 3. Which is the machine now?",
      answer = "Busy: the first line is an average since boot, the lines after it are the last second",
      others = listOf(
        "Idle: the first line is the most precise because it covers the longest time",
        "Half busy: average the first line with the others",
        "Unknown: vmstat needs root to show idle time"
      ),
      explanation = "The first report of vmstat, like the first one of iostat, gives averages since the last boot. Read the lines that follow it.",
      doc = "The 60-second checklist > The ten commands (first line)"
    ),
    ChoiceQuestion(
      text = "uptime shows a load average of 12 on a machine with 4 CPUs, but mpstat shows the CPUs 40% idle. What can explain it?",
      answer = "Linux load also counts tasks in uninterruptible sleep, often waiting for disk I/O",
      others = listOf(
        "The load average is per CPU, so 12 / 4 = 3 is normal",
        "mpstat is wrong whenever the load is above the CPU count",
        "Load counts the network packets waiting in the queue"
      ),
      explanation = "On Linux the load average counts tasks that run, wait for a CPU, or wait in uninterruptible sleep. A high load with idle CPUs points at I/O, not CPU.",
      doc = "The 60-second checklist > The ten commands (load)"
    ),
    ChoiceQuestion(
      text = "free -m shows free 180 and available 2900 on a busy database host. How much memory can a new program get without swapping?",
      answer = "About 2900 MiB: available counts the page cache the kernel can give back",
      others = listOf(
        "About 180 MiB: free is what is left",
        "About 3080 MiB: free plus available",
        "Nothing: a free value that low means the host is swapping"
      ),
      explanation = "The free column leaves out the page cache, which the kernel can drop. available estimates what a new program can get without swapping, so it is the column to read.",
      doc = "The 60-second checklist > The ten commands (free)"
    ),
    ChoiceQuestion(
      text = "pidstat 1 shows one java process at %CPU 190. What does that mean?",
      answer = "It uses about 1.9 CPUs: 100 is one full CPU",
      others = listOf(
        "The number is broken: a share cannot go above 100",
        "It uses 190% of its container limit",
        "It waited for a CPU 90% of the time"
      ),
      explanation = "pidstat's %CPU is the share of one CPU, unless you pass -I. A process with many busy threads goes above 100.",
      doc = "The 60-second checklist > The ten commands (pidstat)"
    ),
    ChoiceQuestion(
      text = "You run free -m inside a container with a 512 MB limit. It shows 7900 MB total. Why?",
      answer = "/proc/meminfo is not per container: free shows the whole machine, and the limit lives in memory.max",
      others = listOf(
        "The limit is not applied until the container uses more than half of it",
        "free inside a container always shows the limit plus the swap",
        "The container has no limit: Docker ignored mem_limit"
      ),
      explanation = "free, vmstat and top read /proc files that show the machine. A container's own limit and usage are in its cgroup files.",
      doc = "The 60-second checklist > From host to container"
    ),
    ChoiceQuestion(
      text = "From the toolbox, sar -n DEV 1 shows almost no traffic, but api serves 50 requests/s. Why?",
      answer = "Each container has its own network namespace: sar shows the toolbox's own interfaces",
      others = listOf(
        "sar only counts traffic that leaves the machine",
        "sar needs a daemon that is not running, so it shows zeros",
        "api's traffic goes over a unix socket, not an interface"
      ),
      explanation = "Run the tool inside api's namespace with nsenter -t $(pgrep -f checkout-api) -n sar -n DEV 1. The binary stays the toolbox's.",
      doc = "The 60-second checklist > From host to container (network namespace)"
    ),
    ChoiceQuestion(
      text = "After an OOM kill and a restart, memory.events of the api container shows oom_kill 0. What does it prove?",
      answer = "Little: the counters can start again after a restart, so check dmesg for the kill",
      others = listOf(
        "That there was no OOM kill: the counter never resets",
        "That the kill came from Java, not from the kernel",
        "That the memory limit was raised at the restart"
      ),
      explanation = "dmesg keeps the kernel's \"Killed process\" line. A restarted container's cgroup counters may start from zero.",
      doc = "The 60-second checklist > From host to container (memory.events)"
    ),
    ChoiceQuestion(
      text = "Checkouts are slow. The checklist shows idle CPUs, no swap, quiet disks and no retransmits. What is most likely?",
      answer = "Requests wait on a software resource, like a pool or a lock, which the kernel does not show as busy",
      others = listOf(
        "Nothing is wrong: an idle machine cannot be slow",
        "The checklist missed a busy CPU: run it again with a longer interval",
        "The disk is the bottleneck: idle disks mean requests do not reach them"
      ),
      explanation = "A thread blocked on a pool or a lock uses no CPU. Look at the app's metrics (db_pool_*) and the database views (pg_stat_activity, pg_locks).",
      doc = "The 60-second checklist > What the checklist cannot see"
    ),
    // ---------------------------------------------------------------- Crisis tools
    ChoiceQuestion(
      text = "Why should sysstat, bpftrace and perf be in the base image before any incident?",
      answer = "A host in trouble may not let you install: no mirror, a full disk or too little memory",
      others = listOf(
        "They collect history only from the moment they are installed",
        "They need a reboot after the install",
        "Installing them restarts every service on the host"
      ),
      explanation = "The incident is the worst moment to install. Ship the tools in every host image and debug container.",
      doc = "Crisis tools > Install before the incident"
    ),
    ChoiceQuestion(
      text = "You want to see which system calls a busy production JVM makes. Why use strace for seconds only?",
      answer = "strace stops the process at every system call, so it can make it many times slower",
      others = listOf(
        "strace fills the disk with a log after a few seconds",
        "strace can only attach to one thread at a time",
        "strace needs the JVM to be restarted with a flag"
      ),
      explanation = "strace uses ptrace and stops the tracee at each call. strace -c -f -p PID gives a short count by call name.",
      doc = "Crisis tools > Install before the incident (strace)"
    ),
    ChoiceQuestion(
      text = "A bpftrace profile with @[comm] = count() shows most samples on one Java thread name. What do you learn?",
      answer = "Which Java thread burns the CPU: the JVM gives its native threads their Java names",
      others = listOf(
        "Which method burns the CPU: comm is the method name",
        "Nothing: every Java thread is named java in the kernel",
        "Which container burns the CPU: comm is the container name"
      ),
      explanation = "comm is the thread name, cut to 15 characters. HotSpot sets it from the Java thread name, so the event loop or a GC thread shows by name.",
      doc = "Crisis tools > Tracing one-liners (comm)"
    ),
    ChoiceQuestion(
      text = "Why does the bpftrace profile one-liner sample at 99 Hz and not 100 Hz?",
      answer = "So the samples do not run in step with other timed activity",
      others = listOf(
        "100 Hz is above the kernel's limit for perf events",
        "99 Hz makes the counts easy to turn into percentages",
        "The kernel rounds 100 Hz down to 99 Hz anyway"
      ),
      explanation = "A sampling rate in step with a timer sees the same moment of every cycle. 99 Hz keeps the profile fair.",
      doc = "Crisis tools > Tracing one-liners (99 Hz)"
    ),
    ChoiceQuestion(
      text = "The toolbox help runs perf top -e cpu-clock instead of the default event. Why?",
      answer = "cpu-clock is a software event, so it works on a VM without hardware counters",
      others = listOf(
        "cpu-clock needs no root, the default event does",
        "cpu-clock samples only one CPU, so it is lighter",
        "The default event only shows kernel functions"
      ),
      explanation = "Hardware events need the PMU, which many VMs do not expose. cpu-clock is driven by a kernel timer.",
      doc = "Crisis tools > Tracing one-liners (perf top)"
    ),
    // ---------------------------------------------------------------- The toolbox in this lab
    ChoiceQuestion(
      text = "Why can pgrep -f checkout-api in the toolbox find api's process?",
      answer = "The toolbox runs with pid: host, so it sees the processes of every container",
      others = listOf(
        "The toolbox and api run from the same image",
        "pgrep asks the docker socket for the processes",
        "api publishes its PID on /metrics"
      ),
      explanation = "With the host PID namespace, top, pidstat and pgrep see every process on the VM, with the VM's PIDs.",
      doc = "The toolbox in this lab > make toolbox and make shell"
    ),
    ChoiceQuestion(
      text = "make toolbox is running and bpftrace fails on Docker Desktop. Can you still play the exercise?",
      answer = "Yes: the checklist, the cgroup files, psql and curl work without BPF",
      others = listOf(
        "No: the toolbox mode needs bpftrace for every scenario",
        "Only after you switch the VM kernel",
        "Only if you open Grafana instead"
      ),
      explanation = "kernel-check says which kernel tools work. Every scenario's proof has commands that need no kernel tracing.",
      doc = "The toolbox in this lab > make toolbox and make shell (kernel-check)"
    )
  )
}

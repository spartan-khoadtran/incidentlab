# Disk and storage

This file covers the disk under a database: the math of a disk, how Postgres uses it, and the disk panels of the lab. The disk exercise (`make new TOPIC=disk`) and the drills in `make quiz` point at its sections. Read [USE method in detail](investigation-guide.md#use-method-in-detail) first: a disk is one more resource with utilization, saturation and errors.

## Disk math

A disk takes I/O requests, serves them one after another or a few at a time, and keeps a queue for the rest. Three numbers describe the work: how many requests per second, how big they are, and how long each one takes.

### IOPS, size and throughput

IOPS is the number of I/O operations completed per second. Throughput is the bytes moved per second.

Throughput = IOPS × I/O size.

- 3000 IOPS of 16 KiB is 3000 × 16 / 1024 ≈ 47 MiB/s.
- 1000 IOPS of 256 KiB is 250 MiB/s.

A disk or a cloud volume has two limits: an IOPS limit and a throughput limit. The one you hit first depends on the I/O size.

| Workload | Typical I/O | Limit hit first |
|---|---|---|
| Database commits (WAL writes with fsync) | Small, a few kB, one fsync each | IOPS |
| Random reads of table pages | 8 kB pages in Postgres | IOPS |
| Backup, bulk copy, sequential scan | Large, 128 kB to 1 MB | Throughput |

How to find the real cap: max IOPS = min(IOPS limit, throughput limit / I/O size). A volume with 3000 IOPS and 125 MiB/s reaches its throughput limit at 500 IOPS of 256 KiB. With 16 KiB I/Os it reaches the IOPS limit first.

An fsync is not free. It sends the written data to the device and then asks the device to flush its cache. So a database that commits often needs many small write I/Os, and the IOPS limit matters more than the throughput limit.

### Utilization and service time

Utilization is the share of time the disk has at least one I/O in flight. It is the `%util` column of `iostat`. In node-exporter it is `rate(node_disk_io_time_seconds_total[1m])`.

The utilization law links it to IOPS: U = X × S.

- X: completed I/Os per second.
- S: the mean service time of one I/O, the time the device works on it.

Example: 200 IOPS at 2 ms each keeps the disk busy 200 × 0.002 = 0.4, so 40% of the time. Turned around, S = U / X: a disk 60% busy at 300 IOPS spends 2 ms per I/O.

Read `%util` with care on an SSD. An SSD and a storage array serve many I/Os at the same time. At 100% busy they can still take more work, because 100% only means that at least one I/O was in flight all the time. On a single spinning disk, 100% busy really is full. This is the time-based utilization of the main guide, see [Time-based and capacity-based](investigation-guide.md#time-based-and-capacity-based).

### Await and the queue

Await is the mean time of one I/O, from when the kernel creates the request to when it completes. It includes the time in the queue and the service time. It is `r_await` and `w_await` in `iostat`. In node-exporter: `rate(node_disk_write_time_seconds_total[1m]) / rate(node_disk_writes_completed_total[1m])`.

The queue is the mean number of I/Os in flight. It is `aqu-sz` in `iostat`, and `rate(node_disk_io_time_weighted_seconds_total[1m])` in node-exporter. It is the saturation of the disk.

Little's Law links the three: queue = IOPS × await. 400 IOPS with an await of 5 ms means 400 × 0.005 = 2 I/Os in flight on average.

Await grows before the disk is full, like any queue. By the M/M/1 model, await ≈ S / (1 − U). With a 1 ms service time:

| Utilization | Await |
|---|---|
| 50% | 2 ms |
| 80% | 5 ms |
| 90% | 10 ms |

So a disk at 80% busy already makes every commit wait about five times its service time. See [Why latency rises before 100%](investigation-guide.md#why-latency-rises-before-100) for the reason.

## Postgres and the disk

Postgres keeps table pages in shared buffers and writes changes to the write-ahead log (WAL) first. Reads that hit a cache never touch the disk. Commits always do, because a commit must reach durable storage before Postgres says it is done.

### WAL and fsync at commit

At commit, a backend asks for the WAL up to its commit record to be written and flushed. On Linux the flush is `fdatasync` by default (`wal_sync_method`). The commit waits for that flush, so commit latency includes one disk flush.

- One flush covers every commit whose WAL is already in the buffers. Commits that arrive together can share one fsync.
- A read-only request does not wait for WAL. When the disk under the WAL is slow, writes like `/checkout` slow down and cached reads like `/products` do not.
- `pg_stat_wal` counts the flushes in `wal_sync` and their time in `wal_sync_time`. The time is only filled when `track_wal_io_timing` is on. The lab turns it on.
- Mean WAL sync time = `rate(pg_wal_sync_seconds_total[1m]) / rate(pg_wal_syncs_total[1m])`. It is the disk's write await plus the cache flush, seen from Postgres.

When another process fills the same disk with writes and fsyncs, the WAL flush waits behind that work. Postgres shows it as more time in `IO / WALSync`, and the disk shows a higher busy share and await. No query plan changed and no lock is held.

### Checkpoints

A checkpoint writes every dirty page in shared buffers to the table files, flushes them, and writes a checkpoint record to the WAL. After a crash, recovery starts from the last checkpoint.

A checkpoint starts when `checkpoint_timeout` passes, or when the WAL is about to pass `max_wal_size`, whichever comes first. `pg_stat_bgwriter` counts the first kind as `checkpoints_timed` and the second as `checkpoints_req` (requested). Postgres requests one when the WAL since the last checkpoint reaches about `max_wal_size / (1 + checkpoint_completion_target)`. It counts in whole 16 MB WAL segments, so the real point can be one or two segments lower. With the default 1 GB that hardly matters. With 64 MB it does.

| Setting | Default | Allowed | Lower value means |
|---|---|---|---|
| `checkpoint_timeout` | 5 min | 30 s to 1 day | More checkpoints, more WAL |
| `checkpoint_completion_target` | 0.9 | 0 to 1 | The writes of each checkpoint squeezed into a shorter time |
| `max_wal_size` | 1 GB | 2 MB and up (a soft limit) | Requested checkpoints come sooner under write load |

- The writes of a checkpoint are spread over `checkpoint_completion_target` of the interval. With 5 min and 0.9, a checkpoint writes for about 4.5 min. With 30 s and 0.1, it writes the same pages in about 3 s, then flushes them at once. That is a burst of disk I/O every 30 s.
- The first change to a page after a checkpoint writes an image of the whole page to the WAL (a full page image). A shorter interval means more full page images, so more WAL. The WAL jumps right after each checkpoint.
- The three settings take effect on a reload. `ALTER SYSTEM SET` writes them to `postgresql.auto.conf`, and `SELECT pg_reload_conf()` applies them. No restart is needed.

In `pg_stat_bgwriter`, `checkpoint_write_time` includes the pauses that spread a checkpoint out. A long write time is healthy. `checkpoint_sync_time` is the flush at the end, and a high value means the flush hit a busy disk.

### Wait events

`pg_stat_activity` shows what each server process waits for right now, in `wait_event_type` and `wait_event`.

| Type | Means |
|---|---|
| `Activity` | An idle process in its main loop |
| `Client` | Waiting for the application on the socket |
| `IO` | Waiting for an I/O to complete |
| `LWLock` | Waiting for a lightweight lock (short, in memory) |
| `Lock` | Waiting for a heavyweight lock (rows, tables) |
| `IPC` | Waiting for another server process |
| `Timeout` | Waiting for a timeout to expire |
| `BufferPin`, `Extension` | A buffer pin, or something an extension defines |

The names that point at the disk:

| Event | Means |
|---|---|
| `IO / WALSync` | Waiting for a WAL file to reach durable storage |
| `IO / WALWrite` | Waiting for a write to a WAL file |
| `LWLock / WALWrite` | Waiting for WAL buffers to be written to disk (another process holds the write) |
| `IO / DataFileRead` | Waiting for a read from a table or index file |
| `IO / DataFileWrite` | Waiting for a write to a table or index file |
| `IO / DataFileSync` | Waiting for changes to a table or index file to reach durable storage |
| `Timeout / CheckpointWriteDelay` | The checkpointer pausing between writes to spread them out |

`Activity` and `Client` are idle waits, so leave them out when you look for a bottleneck.

A wait event is a snapshot. A WAL sync of 1 ms at 30 per second is in progress only 3% of the time, so one look at `pg_stat_activity` rarely catches it. Sample often and average. By Little's Law, the mean number of processes in a wait = the rate of that wait × its mean duration: 30/s × 0.001 s = 0.03. The lab samples every 100 ms and exports the mean as `pg_wait_events`.

### IOPS limits in the cloud

A cloud volume has its own IOPS and throughput limits, below what the hardware can do. An AWS gp3 volume starts at 3000 IOPS and 125 MiB/s, and you pay to raise them. A gp2 volume has a baseline of 3 IOPS per GiB (at least 100) and bursts to 3000 IOPS while it has I/O credits. EBS counts an SSD I/O of up to 256 KiB as one operation.

Where the limit sits decides what each layer sees:

| Limit | The guest OS sees | The database sees |
|---|---|---|
| None, the disk itself is busy | Busy share up, await up, queue up | Slow I/O waits |
| A cloud volume limit, below the guest | Await and queue up, IOPS flat at the limit | Slow I/O waits |
| A cgroup limit (`io.max`), above the device | Almost nothing: the I/O waits in the cgroup before it reaches the device, so await stays low | Slow I/O waits |

Two signs of a limit: IOPS stay flat at one round number while demand grows, and the database waits on I/O while throughput stays low. When I/O arrives faster than the limit, the backlog grows by the difference every second: 120 writes/s against a limit of 80 adds 40 waiting writes per second. With cgroup v2, a container's limit is one line in its `io.max` file, for example `254:16 wiops=60`.

## Disk panels in this lab

Dashboard `02 · USE by layer` has the Postgres server view above the Kafka row and the host disk row after the network row. Read them together: the disk row says how busy the disk is, the Postgres row says who waits for it.

### The host disk row

Row "Disk (host, node-exporter)". node-exporter reads `/proc/diskstats` of the Docker host (the VM on a laptop). It shows whole disks and hides partitions by default. Every container's volume sits on the same disk.

| Panel | PromQL | Read it as |
|---|---|---|
| IOPS by device | `rate(node_disk_writes_completed_total[1m])`, reads the same way | Completed I/Os per second |
| Throughput by device | `rate(node_disk_written_bytes_total[1m])` | Bytes per second |
| Await | `rate(node_disk_write_time_seconds_total[1m]) / rate(node_disk_writes_completed_total[1m])` | Mean time per I/O, queue included |
| Busy share | `100 * rate(node_disk_io_time_seconds_total[1m])` | `%util`: time with at least one I/O in flight |
| Queue | `rate(node_disk_io_time_weighted_seconds_total[1m])` | `aqu-sz`: mean I/Os in flight, the saturation |

The queue panel divided by the IOPS panel gives the await, by Little's Law. Use it to check that the panels agree.

The host has no disk errors to show here. On a real host, read `dmesg` for I/O errors.

### The Postgres row

Row "Postgres (server side)". pgobserver polls the statistics views every 2 seconds.

| Panel | Series | Read it as |
|---|---|---|
| Wait events | `pg_wait_events{type,event}` | Mean processes waiting on each event, sampled every 100 ms |
| Mean WAL sync time | `rate(pg_wal_sync_seconds_total[1m]) / rate(pg_wal_syncs_total[1m])` | What one commit waits for its flush |
| WAL written per second | `rate(pg_wal_bytes_total[1m])` | WAL volume. It jumps after each checkpoint |
| Checkpoints in the last minute | `increase(pg_checkpoints_total[1m])` by `kind` | `timed` or `requested`. Healthy shows one timed checkpoint every 5 minutes |
| Checkpoint write and sync | `rate(pg_checkpoint_write_seconds_total[1m])`, `rate(pg_checkpoint_sync_seconds_total[1m])` | Moves once per checkpoint, when it ends |

Compare the mean WAL sync time with the host await. Both rise when the disk is busy. A WAL sync time that rises while the host await stays flat points at a limit above the device, such as a cgroup `io.max`. Also compare with "Postgres: mean exec time per query" in row 5 of `01 · RED by layer`. It stays flat when the disk is slow, because Postgres times each statement but not the WAL flush at its commit.

package com.c0x12c.incidentlab.metrics

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** What the kernel reports about the container this process runs in. Counters are totals since the cgroup was created. */
data class CgroupStats(
  val cpuUsageSeconds: Double,
  /** cgroup v2 only: `user_usec` and `system_usec` from cpu.stat. */
  val cpuUserSeconds: Double,
  val cpuSystemSeconds: Double,
  val cpuPeriods: Double,
  val cpuThrottledPeriods: Double,
  val cpuThrottledSeconds: Double,
  val cpuLimitCores: Double,
  val memoryUsageBytes: Double,
  val memoryLimitBytes: Double,
  val oomKills: Double,
  /** PSI totals in seconds keyed by (resource, kind): resource cpu, memory or io; kind some or full. */
  val pressureWaitingSeconds: Map<Pair<String, String>, Double>
) {
  companion object {
    val EMPTY = CgroupStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
  }
}

/** Reads cgroup v2 first, v1 as a fallback. A missing or unreadable file reads as zero, never as an error. */
class CgroupReader(private val root: Path = Path.of("/sys/fs/cgroup")) {

  private val v2: Boolean = Files.exists(root.resolve("cgroup.controllers"))

  fun read(): CgroupStats = if (v2) readV2() else readV1()

  private fun readV2(): CgroupStats {
    val cpu = keyValues(text(root.resolve("cpu.stat")))
    val cpuMax = text(root.resolve("cpu.max"))?.split(Regex("\\s+"))
    val limit = cpuMax?.let { parts ->
      val quota = parts.getOrNull(0)
      val period = parts.getOrNull(1)?.toDoubleOrNull() ?: DEFAULT_PERIOD_MICROS
      if (quota == null || quota == "max") 0.0 else (quota.toDoubleOrNull() ?: 0.0) / period
    } ?: 0.0
    val memoryMax = text(root.resolve("memory.max"))
    val pressure = PRESSURE_RESOURCES.flatMap { resource ->
      psi(text(root.resolve("$resource.pressure"))).map { (kind, total) -> (resource to kind) to total }
    }.toMap()
    return CgroupStats(
      cpuUsageSeconds = (cpu["usage_usec"] ?: 0.0) / MICROS_PER_SECOND,
      cpuUserSeconds = (cpu["user_usec"] ?: 0.0) / MICROS_PER_SECOND,
      cpuSystemSeconds = (cpu["system_usec"] ?: 0.0) / MICROS_PER_SECOND,
      cpuPeriods = cpu["nr_periods"] ?: 0.0,
      cpuThrottledPeriods = cpu["nr_throttled"] ?: 0.0,
      cpuThrottledSeconds = (cpu["throttled_usec"] ?: 0.0) / MICROS_PER_SECOND,
      cpuLimitCores = limit,
      memoryUsageBytes = text(root.resolve("memory.current"))?.toDoubleOrNull() ?: 0.0,
      memoryLimitBytes = if (memoryMax == null || memoryMax == "max") 0.0 else memoryMax.toDoubleOrNull() ?: 0.0,
      oomKills = keyValues(text(root.resolve("memory.events")))["oom_kill"] ?: 0.0,
      pressureWaitingSeconds = pressure
    )
  }

  private fun readV1(): CgroupStats {
    val cpuDir = listOf("cpu,cpuacct", "cpu", "cpuacct").map { root.resolve(it) }.firstOrNull { Files.exists(it) } ?: root
    val usageNanos = text(cpuDir.resolve("cpuacct.usage"))?.toDoubleOrNull()
      ?: text(root.resolve("cpuacct").resolve("cpuacct.usage"))?.toDoubleOrNull()
      ?: 0.0
    val cpu = keyValues(text(cpuDir.resolve("cpu.stat")))
    val quota = text(cpuDir.resolve("cpu.cfs_quota_us"))?.toDoubleOrNull() ?: -1.0
    val period = text(cpuDir.resolve("cpu.cfs_period_us"))?.toDoubleOrNull() ?: DEFAULT_PERIOD_MICROS
    val memoryDir = root.resolve("memory")
    val memoryLimit = text(memoryDir.resolve("memory.limit_in_bytes"))?.toDoubleOrNull() ?: 0.0
    return CgroupStats(
      cpuUsageSeconds = usageNanos / NANOS_PER_SECOND,
      cpuUserSeconds = 0.0,
      cpuSystemSeconds = 0.0,
      cpuPeriods = cpu["nr_periods"] ?: 0.0,
      cpuThrottledPeriods = cpu["nr_throttled"] ?: 0.0,
      cpuThrottledSeconds = (cpu["throttled_time"] ?: 0.0) / NANOS_PER_SECOND,
      cpuLimitCores = if (quota > 0) quota / period else 0.0,
      memoryUsageBytes = text(memoryDir.resolve("memory.usage_in_bytes"))?.toDoubleOrNull() ?: 0.0,
      memoryLimitBytes = if (memoryLimit > V1_UNLIMITED_THRESHOLD) 0.0 else memoryLimit,
      oomKills = keyValues(text(memoryDir.resolve("memory.oom_control")))["oom_kill"] ?: 0.0,
      pressureWaitingSeconds = emptyMap()
    )
  }

  private fun text(path: Path): String? =
    try {
      Files.readString(path).trim()
    } catch (e: Exception) {
      null
    }

  private fun keyValues(text: String?): Map<String, Double> =
    text.orEmpty().lines().mapNotNull { line ->
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size == 2) parts[1].toDoubleOrNull()?.let { parts[0] to it } else null
    }.toMap()

  /** A PSI file has one line per kind: `some avg10=0.00 avg60=0.00 avg300=0.00 total=12345` with total in microseconds. */
  private fun psi(text: String?): Map<String, Double> =
    text.orEmpty().lines().mapNotNull { line ->
      val parts = line.trim().split(Regex("\\s+"))
      val kind = parts.firstOrNull() ?: return@mapNotNull null
      val total = parts.firstOrNull { it.startsWith("total=") }?.removePrefix("total=")?.toDoubleOrNull() ?: return@mapNotNull null
      kind to total / MICROS_PER_SECOND
    }.toMap()

  private companion object {
    const val DEFAULT_PERIOD_MICROS = 100_000.0
    const val MICROS_PER_SECOND = 1_000_000.0
    const val NANOS_PER_SECOND = 1_000_000_000.0
    const val V1_UNLIMITED_THRESHOLD = 1.0e18
    val PRESSURE_RESOURCES = listOf("cpu", "memory", "io")
  }
}

/**
 * The container (cgroup) USE layer: CPU usage against the CFS limit, throttled periods, memory
 * against the limit, OOM kills and PSI. One read serves every meter of a scrape; the snapshot is
 * reused for [ttl] so a scrape never touches the files more than once.
 */
class CgroupMetrics(
  private val reader: CgroupReader = CgroupReader(),
  private val clock: Clock = Clock.systemUTC(),
  private val ttl: Duration = Duration.ofMillis(500)
) : MeterBinder {

  private val cached = AtomicReference(Cached(CgroupStats.EMPTY, 0L))

  override fun bindTo(registry: MeterRegistry) {
    FunctionCounter.builder("cgroup_cpu_usage_seconds", this) { it.latest().cpuUsageSeconds }
      .description("CPU time used by the container").register(registry)
    FunctionCounter.builder("cgroup_cpu_mode_seconds", this) { it.latest().cpuUserSeconds }
      .description("CPU time used by the container, by mode (user code or the kernel on its behalf)")
      .tags("mode", "user").register(registry)
    FunctionCounter.builder("cgroup_cpu_mode_seconds", this) { it.latest().cpuSystemSeconds }
      .description("CPU time used by the container, by mode (user code or the kernel on its behalf)")
      .tags("mode", "system").register(registry)
    FunctionCounter.builder("cgroup_cpu_periods", this) { it.latest().cpuPeriods }
      .description("CFS enforcement periods").register(registry)
    FunctionCounter.builder("cgroup_cpu_throttled_periods", this) { it.latest().cpuThrottledPeriods }
      .description("CFS periods in which the container was throttled").register(registry)
    FunctionCounter.builder("cgroup_cpu_throttled_seconds", this) { it.latest().cpuThrottledSeconds }
      .description("Total time the container was throttled").register(registry)
    Gauge.builder("cgroup_cpu_limit_cores", this) { it.latest().cpuLimitCores }
      .description("CPU limit of the container in cores (0 = no limit)").register(registry)
    Gauge.builder("cgroup_memory_usage_bytes", this) { it.latest().memoryUsageBytes }
      .description("Memory used by the container (includes page cache)").register(registry)
    Gauge.builder("cgroup_memory_limit_bytes", this) { it.latest().memoryLimitBytes }
      .description("Memory limit of the container (0 = no limit)").register(registry)
    FunctionCounter.builder("cgroup_memory_oom_kills", this) { it.latest().oomKills }
      .description("OOM kills inside this cgroup").register(registry)
    for (resource in listOf("cpu", "memory", "io")) {
      for (kind in listOf("some", "full")) {
        FunctionCounter.builder("cgroup_pressure_waiting_seconds", this) { it.latest().pressureWaitingSeconds[resource to kind] ?: 0.0 }
          .description("PSI: time tasks in this cgroup waited for a resource")
          .tags("resource", resource, "kind", kind)
          .register(registry)
      }
    }
  }

  internal fun latest(): CgroupStats {
    val now = clock.millis()
    val current = cached.get()
    if (now - current.at < ttl.toMillis()) {
      return current.stats
    }
    val fresh = try {
      reader.read()
    } catch (e: Exception) {
      CgroupStats.EMPTY
    }
    cached.set(Cached(fresh, now))
    return fresh
  }

  private class Cached(val stats: CgroupStats, val at: Long)
}

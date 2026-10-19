package com.c0x12c.incidentlab.metrics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CgroupReaderTest {

  @Test
  fun `reads a cgroup v2 tree`(@TempDir root: Path) {
    Files.writeString(root.resolve("cgroup.controllers"), "cpu memory io")
    Files.writeString(root.resolve("cpu.stat"), "usage_usec 2500000\nuser_usec 2000000\nsystem_usec 500000\nnr_periods 40\nnr_throttled 10\nthrottled_usec 750000\n")
    Files.writeString(root.resolve("cpu.max"), "20000 100000")
    Files.writeString(root.resolve("memory.current"), "123456")
    Files.writeString(root.resolve("memory.max"), "536870912")
    Files.writeString(root.resolve("memory.events"), "low 0\nhigh 0\nmax 3\noom 1\noom_kill 1\n")
    Files.writeString(root.resolve("cpu.pressure"), "some avg10=0.00 avg60=0.00 avg300=0.00 total=1500000\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n")

    val stats = CgroupReader(root).read()

    assertThat(stats.cpuUsageSeconds).isEqualTo(2.5)
    assertThat(stats.cpuUserSeconds).isEqualTo(2.0)
    assertThat(stats.cpuSystemSeconds).isEqualTo(0.5)
    assertThat(stats.cpuPeriods).isEqualTo(40.0)
    assertThat(stats.cpuThrottledPeriods).isEqualTo(10.0)
    assertThat(stats.cpuThrottledSeconds).isEqualTo(0.75)
    assertThat(stats.cpuLimitCores).isEqualTo(0.2)
    assertThat(stats.memoryUsageBytes).isEqualTo(123456.0)
    assertThat(stats.memoryLimitBytes).isEqualTo(536870912.0)
    assertThat(stats.oomKills).isEqualTo(1.0)
    assertThat(stats.pressureWaitingSeconds[("cpu" to "some")]).isEqualTo(1.5)
    assertThat(stats.pressureWaitingSeconds[("cpu" to "full")]).isEqualTo(0.0)
  }

  @Test
  fun `no limit and missing files read as zero`(@TempDir root: Path) {
    Files.writeString(root.resolve("cgroup.controllers"), "cpu memory")
    Files.writeString(root.resolve("cpu.max"), "max 100000")
    Files.writeString(root.resolve("memory.max"), "max")

    val stats = CgroupReader(root).read()

    assertThat(stats.cpuLimitCores).isEqualTo(0.0)
    assertThat(stats.memoryLimitBytes).isEqualTo(0.0)
    assertThat(stats.cpuUsageSeconds).isEqualTo(0.0)
    assertThat(stats.cpuSystemSeconds).isEqualTo(0.0)
    assertThat(stats.pressureWaitingSeconds).isEqualTo(emptyMap())
  }

  @Test
  fun `CPU time is exported by mode`(@TempDir root: Path) {
    Files.writeString(root.resolve("cgroup.controllers"), "cpu memory")
    Files.writeString(root.resolve("cpu.stat"), "usage_usec 4000000\nuser_usec 1000000\nsystem_usec 3000000\n")
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    CgroupMetrics(CgroupReader(root)).bindTo(registry)

    val text = registry.scrape()
    assertThat(text).contains("cgroup_cpu_mode_seconds_total{mode=\"user\"} 1.0")
    assertThat(text).contains("cgroup_cpu_mode_seconds_total{mode=\"system\"} 3.0")
  }
}

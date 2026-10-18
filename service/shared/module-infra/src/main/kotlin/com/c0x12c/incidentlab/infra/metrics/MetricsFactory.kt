package com.c0x12c.incidentlab.infra.metrics

import com.c0x12c.incidentlab.metrics.CgroupMetrics
import com.c0x12c.incidentlab.metrics.NetstatMetrics
import com.c0x12c.incidentlab.metrics.JfrMetrics
import com.c0x12c.incidentlab.metrics.JvmMetrics
import com.c0x12c.incidentlab.metrics.ProcessMetrics
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.prometheus.metrics.model.registry.PrometheusRegistry
import jakarta.inject.Singleton

// One registry per process, built by hand rather than through micronaut-micrometer: the lab's
// panels name every series, so no auto binder may add or rename one. The process, cgroup and
// netstat binders are the Runtime, Container and Network rows of the USE dashboard, the JVM and JFR
// binders its JVM row. The JVM binder goes first: its GC bucket filter only shapes meters registered
// after it. Every binder is a bean of its own because Micrometer only holds a weak reference to a
// gauge's state object: a binder nobody else references is collected, and its gauges read NaN from
// then on.
@Factory
class MetricsFactory {

  @Singleton
  fun processMetrics(): ProcessMetrics = ProcessMetrics()

  @Singleton
  fun cgroupMetrics(): CgroupMetrics = CgroupMetrics()

  @Singleton
  fun netstatMetrics(): NetstatMetrics = NetstatMetrics()

  @Singleton
  @Bean(preDestroy = "close")
  fun jvmMetrics(): JvmMetrics = JvmMetrics()

  @Singleton
  @Bean(preDestroy = "close")
  fun jfrMetrics(): JfrMetrics = JfrMetrics()

  @Singleton
  fun prometheusMeterRegistry(process: ProcessMetrics, cgroup: CgroupMetrics, netstat: NetstatMetrics, jvm: JvmMetrics, jfr: JfrMetrics): PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry(), Clock.SYSTEM, CurrentSpanContext()).also { registry ->
      jvm.bindTo(registry)
      jfr.bindTo(registry)
      process.bindTo(registry)
      cgroup.bindTo(registry)
      netstat.bindTo(registry)
    }
}

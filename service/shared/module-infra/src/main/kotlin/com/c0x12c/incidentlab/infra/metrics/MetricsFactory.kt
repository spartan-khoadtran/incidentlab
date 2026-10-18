package com.c0x12c.incidentlab.infra.metrics

import com.c0x12c.incidentlab.metrics.CgroupMetrics
import com.c0x12c.incidentlab.metrics.ProcessMetrics
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.micronaut.context.annotation.Factory
import io.prometheus.metrics.model.registry.PrometheusRegistry
import jakarta.inject.Singleton

// One registry per process, built by hand rather than through micronaut-micrometer: the lab's
// panels name every series, so no auto binder may add or rename one. The process and cgroup
// binders are the Runtime and Container rows of the USE dashboard. They are beans of their own
// because Micrometer only holds a weak reference to a gauge's state object: a binder nobody else
// references is collected, and its gauges read NaN from then on.
@Factory
class MetricsFactory {

  @Singleton
  fun processMetrics(): ProcessMetrics = ProcessMetrics()

  @Singleton
  fun cgroupMetrics(): CgroupMetrics = CgroupMetrics()

  @Singleton
  fun prometheusMeterRegistry(process: ProcessMetrics, cgroup: CgroupMetrics): PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry(), Clock.SYSTEM, CurrentSpanContext()).also { registry ->
      process.bindTo(registry)
      cgroup.bindTo(registry)
    }
}

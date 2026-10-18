package com.c0x12c.incidentlab.metrics

/** Histogram edges in seconds. Every service uses the same ones so the dashboards can sum across jobs. */
object LatencyBuckets {
  val LATENCY = doubleArrayOf(
    0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.25,
    0.4, 0.6, 1.0, 1.5, 2.5, 4.0, 6.0, 10.0
  )
  val LOOP_LAG = doubleArrayOf(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5)
  val END_TO_END = doubleArrayOf(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1200.0)
}

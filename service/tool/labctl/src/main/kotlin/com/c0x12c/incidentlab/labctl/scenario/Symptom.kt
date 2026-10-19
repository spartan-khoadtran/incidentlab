package com.c0x12c.incidentlab.labctl.scenario

/**
 * What a ticket already shows. It decides which signals and causes an exercise offers, so it may only
 * name what the ticket text says: every scenario with the same symptom offers the same options.
 */
enum class Symptom(val label: String) {
  SLOW_ONE_ROUTE("One route is slow, the others are fine"),
  SLOW_EVERY_ROUTE("Every route is slow"),
  TAIL_ONLY("Only the tail moved: short stalls or spikes, the median is flat"),
  ERRORS("Requests fail: 5xx, 503s or timeouts"),
  RATE_CHANGED("A request or query rate changed at one layer"),
  WORK_LATE("Async work is late: orders show up late, lag grows"),
  RESOURCE_ALARM("A resource alarm with no clear user impact yet: CPU, memory, forks")
}

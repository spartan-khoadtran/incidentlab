package com.c0x12c.incidentlab.labctl.scenario

/**
 * What a ticket already shows. It decides which signals and causes an exercise offers, so it may only
 * name what the ticket text says: every scenario with the same symptom offers the same options.
 */
enum class Symptom(val label: String) {
  SLOW_ONE_ROUTE("One route is slow, the others are fine"),
  SLOW_EVERY_ROUTE("Every route is slower"),
  SLOW_BUT_API_FINE("Users see it slow, but api's own p99 looks normal"),
  TAIL_ONLY("Only the tail moved: short stalls or spikes, the median is flat"),
  ERRORS("Requests fail: 5xx, 503s or timeouts"),
  RATE_CHANGED("A request or query rate moved at one layer"),
  WORK_LATE("Async work is late: orders show up late"),
  RESOURCE_ALARM("A CPU or memory alarm, and users see no problem yet"),
  CHANGE_TO_JUDGE("A planned change went out, and you judge what it did")
}

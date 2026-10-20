package com.c0x12c.incidentlab.labctl.scenario

/**
 * What a ticket already shows. It decides which signals and causes an exercise offers, so it may only
 * name what the ticket text says: every scenario with the same symptom offers the same options.
 * When a ticket fits more than one symptom, the first one in this order wins.
 */
enum class Symptom {
  /** Async work is late: orders show up late. */
  WORK_LATE,

  /** A planned change went out, and the ticket asks what it did. */
  CHANGE_TO_JUDGE,

  /** Requests fail, and the next layer says it saw no error. */
  ERRORS_UNSEEN,

  /** Requests fail: 5xx errors or timeouts. */
  ERRORS,

  /** Only the tail moved: short stalls or spikes while the median is flat. */
  TAIL_ONLY,

  /** Users see it slow, but api's own p99 looks normal. */
  SLOW_BUT_API_FINE,

  /** The ticket says every route is slower, or names more than one. */
  SLOW_EVERY_ROUTE,

  /** The ticket names one slow route. */
  SLOW_ONE_ROUTE,

  /** A request or query rate moved at one layer. */
  RATE_CHANGED,

  /** A CPU or memory alarm, and users see no problem yet. */
  RESOURCE_ALARM
}

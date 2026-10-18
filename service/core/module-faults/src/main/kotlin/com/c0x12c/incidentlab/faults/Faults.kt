package com.c0x12c.incidentlab.faults

import java.util.concurrent.atomic.AtomicLong

/**
 * Baseline defaults plus the overrides the lab controller pushes. A key only does something when
 * the service reads it, so every service declares its own defaults and the controller may send any
 * subset. Values arrive as parsed JSON (numbers, booleans, strings, maps), hence the typed readers.
 */
class Faults(defaults: Map<String, Any?>) {

  val defaults: Map<String, Any?> = defaults.toMap()

  @Volatile
  var overrides: Map<String, Any?> = emptyMap()
    private set

  private val changes = AtomicLong()

  /** Bumped on every effective change, so a poller compares one number instead of two maps. */
  val version: Long
    get() = changes.get()

  fun get(key: String): Any? = if (overrides.containsKey(key)) overrides[key] else defaults[key]

  fun has(key: String): Boolean = get(key) != null

  fun int(key: String): Int = number(key)?.toInt() ?: 0

  fun long(key: String): Long = number(key)?.toLong() ?: 0L

  fun double(key: String): Double = number(key)?.toDouble() ?: 0.0

  fun bool(key: String): Boolean =
    when (val value = get(key)) {
      is Boolean -> value
      is Number -> value.toInt() != 0
      is String -> value.toBooleanStrictOrNull() ?: false
      else -> false
    }

  fun string(key: String): String? = get(key)?.toString()

  fun map(key: String): Map<String, Any?> =
    (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

  /** Replaces every override at once. Nulls are dropped, so a null from the controller clears a key. */
  fun replace(next: Map<String, Any?>) {
    val cleaned = next.filterValues { it != null }
    if (cleaned != overrides) {
      overrides = cleaned
      changes.incrementAndGet()
    }
  }

  fun reset() {
    replace(emptyMap())
  }

  fun snapshot(): FaultSnapshot = FaultSnapshot(defaults = defaults, overrides = overrides, effective = defaults + overrides)

  private fun number(key: String): Number? =
    when (val value = get(key)) {
      is Number -> value
      is String -> value.toDoubleOrNull()
      else -> null
    }
}

data class FaultSnapshot(
  val defaults: Map<String, Any?>,
  val overrides: Map<String, Any?>,
  val effective: Map<String, Any?>
)

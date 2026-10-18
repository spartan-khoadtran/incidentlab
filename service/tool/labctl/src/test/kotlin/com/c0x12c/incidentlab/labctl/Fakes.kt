package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.control.Sample

/** A terminal that answers from a script and keeps every printed line. */
class ScriptedTerminal(vararg answers: String?) : Terminal {
  private val answers = ArrayDeque(answers.toList())
  val lines = mutableListOf<String>()

  override fun out(line: String) {
    lines.add(line)
  }

  override fun ask(prompt: String): String? = answers.removeFirstOrNull()
}

/**
 * Prometheus as a function of the expression and the evaluation time. `up = false` is Prometheus
 * not answering. Every query is recorded.
 */
class FakePrometheus(
  private val up: Boolean = true,
  private val value: (expr: String, at: Double) -> Double?
) : LabProbe {
  val queries = mutableListOf<String>()

  override fun cpuCount(): Int = 4

  override fun productDetailSeconds(): Double? = null

  override fun tenants(): Map<String, Int>? = null

  override fun serviceStatus(): Map<String, String?> = mapOf("api" to null)

  override fun rangeQuery(expr: String, start: Double, end: Double, step: Double): List<Sample>? {
    queries += expr
    if (!up) return null
    return generateSequence(start) { it + step }.takeWhile { it <= end + EPSILON }.mapNotNull { at -> value(expr, at)?.let { Sample(at, it) } }.toList()
  }

  override fun instantQuery(expr: String, at: Double?): Double? {
    queries += expr
    if (!up) return null
    return value(expr, at ?: 0.0)
  }

  private companion object {
    const val EPSILON = 1e-6
  }
}

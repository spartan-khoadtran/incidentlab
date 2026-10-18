package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.control.Sample

/**
 * A terminal that answers from a script and keeps every printed line. [beforeAnswer] runs before
 * each answer with its number from 1, so a test can change the lab while a prompt waits.
 */
class ScriptedTerminal(vararg answers: String?) : Terminal {
  private val answers = ArrayDeque(answers.toList())
  private var asked = 0
  val lines = mutableListOf<String>()
  var beforeAnswer: (Int) -> Unit = {}

  override fun out(line: String) {
    lines.add(line)
  }

  override fun ask(prompt: String): String? {
    beforeAnswer(++asked)
    return answers.removeFirstOrNull()
  }
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

/** Grafana's annotations in memory: each one gets the next id, and a delete is recorded. */
class FakeAnnotator : Annotator {
  data class Mark(val id: Long, val text: String, val tags: List<String>, val at: Double)

  val written = mutableListOf<Mark>()
  val deleted = mutableListOf<Long>()

  /** The texts in the order they were written, deleted ones too. */
  val texts: List<String> get() = written.map { it.text }

  /** What the dashboards still show. */
  val live: List<Mark> get() = written.filter { it.id !in deleted }

  override fun annotate(text: String, tags: List<String>, atSeconds: Double): Long = (written.size + 1L).also { written += Mark(it, text, tags, atSeconds) }

  override fun delete(id: Long) {
    deleted += id
  }
}

package com.c0x12c.incidentlab.labctl

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.quiz.Concepts
import com.c0x12c.incidentlab.labctl.quiz.Numbers
import com.c0x12c.incidentlab.labctl.quiz.Quiz
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Every `doc` pointer, written as `Section > Subsection (note)`, must name real headings of the guide. */
class GuidePointersTest {

  private val guide: Path = Path.of("../../..", Quiz.GUIDE)

  private val drillPointers: List<Pair<String, String>> =
    Numbers.generators.mapIndexed { index, generate -> "number $index" to generate(Random(1)).doc } +
      Concepts.all.map { "concept '${it.text.take(40)}'" to it.doc }

  @Test
  fun `every scenario and quiz pointer names a heading of the guide`() {
    assertThat(Files.exists(guide)).isTrue()
    val headings = headings(Files.readAllLines(guide))
    val pointers = ScenarioCatalog.all.map { it.id to it.doc } + drillPointers
    val broken = pointers.flatMap { (owner, doc) ->
      doc.split("; ").mapNotNull { part ->
        val (section, subsection) = parse(part)
        val subsections = headings[section]
        when {
          subsections == null -> "$owner: no section '$section'"
          subsection != null && subsection !in subsections -> "$owner: no subsection '$subsection' under '$section'"
          else -> null
        }
      }
    }
    assertThat(broken).isEmpty()
  }

  /** The drills alone must touch every subsection, so `make quiz` practises the whole guide without the lab. */
  @Test
  fun `the drills cover every subsection of the guide`() {
    val headings = headings(Files.readAllLines(guide))
    val covered = drillPointers.flatMap { (_, doc) -> doc.split("; ").map { parse(it) } }.toSet()
    val uncovered = headings.filterKeys { it !in UNDRILLED_SECTIONS }.flatMap { (section, subsections) ->
      if (subsections.isEmpty()) {
        listOfNotNull(section.takeIf { covered.none { (s, _) -> s == section } })
      } else {
        subsections.filter { (section to it) !in covered }.map { "$section > $it" }
      }
    }
    assertThat(uncovered).isEmpty()
  }

  private fun parse(pointer: String): Pair<String, String?> {
    val bare = pointer.replace(NOTE, "").trim()
    val parts = bare.split(" > ")
    return parts[0] to parts.getOrNull(1)
  }

  private fun headings(lines: List<String>): Map<String, Set<String>> {
    val out = linkedMapOf<String, MutableSet<String>>()
    var section: String? = null
    for (line in lines) {
      when {
        line.startsWith("## ") -> section = line.removePrefix("## ").trim().also { out[it] = mutableSetOf() }
        line.startsWith("### ") -> section?.let { out.getValue(it).add(line.removePrefix("### ").trim()) }
      }
    }
    return out
  }

  private companion object {
    val NOTE = Regex("""\s*\([^)]*\)$""")
    val UNDRILLED_SECTIONS = setOf("References")
  }
}

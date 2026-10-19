package com.c0x12c.incidentlab.labctl

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import com.c0x12c.incidentlab.labctl.bench.BenchExercise
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Concepts
import com.c0x12c.incidentlab.labctl.quiz.Numbers
import com.c0x12c.incidentlab.labctl.ramp.RampExercise
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * Every `doc` pointer, written as `Section > Subsection (note)`, must name real headings of the docs,
 * and [GuideIndex] must know which file owns each section.
 */
class GuidePointersTest {

  /** One `## ` section of a docs file, with its `### ` subsections. */
  private class Section(val file: String, val title: String, val subsections: MutableSet<String> = linkedSetOf())

  private val sections: List<Section> = Path.of("../../..", "docs").listDirectoryEntries("*.md").sortedBy { it.name }.flatMap { sections(it) }

  private val byTitle: Map<String, Section> = sections.associateBy { it.title }

  private val drillPointers: List<Pair<String, String>> =
    Numbers.generators.mapIndexed { index, generate -> "number $index" to generate(Random(1)).doc } +
      Concepts.all.map { "concept '${it.text.take(40)}'" to it.doc }

  @Test
  fun `a section title is unique across the docs files`() {
    assertThat(sections).isNotEmpty()
    val duplicates = sections.groupBy { it.title }.filterValues { it.size > 1 }
      .map { (title, owners) -> "section '$title' is in ${owners.joinToString(" and ") { it.file }}" }
    assertThat(duplicates).isEmpty()
  }

  @Test
  fun `GuideIndex names every section with its file, and nothing else`() {
    val problems = sections.mapNotNull { section ->
      when (val file = GuideIndex.fileOf[section.title]) {
        null -> "${section.file}: section '${section.title}' is missing from GuideIndex"
        section.file -> null
        else -> "GuideIndex puts section '${section.title}' in $file, but it is in ${section.file}"
      }
    } + (GuideIndex.fileOf.keys - byTitle.keys).map { "GuideIndex: section '$it' (${GuideIndex.fileOf[it]}) is in no docs file" }
    assertThat(problems).isEmpty()
  }

  @Test
  fun `every scenario and quiz pointer names a heading of the docs`() {
    val pointers = ScenarioCatalog.all.map { it.id to it.doc } + listOf("make ramp" to RampExercise.DOC, "make bench" to BenchExercise.DOC) + drillPointers
    val broken = pointers.flatMap { (owner, doc) ->
      doc.split("; ").mapNotNull { part ->
        val (title, subsection) = parse(part)
        val section = byTitle[title]
        when {
          section == null -> "$owner: no section '$title' in any docs file"
          subsection != null && subsection !in section.subsections -> "$owner: no subsection '$subsection' under '$title' in ${section.file}"
          else -> null
        }
      }
    }
    assertThat(broken).isEmpty()
  }

  /** The drills alone must touch every subsection, so `make quiz` practises the whole guide without the lab. */
  @Test
  fun `the drills cover every subsection of the docs`() {
    val covered = drillPointers.flatMap { (_, doc) -> doc.split("; ").map { parse(it) } }.toSet()
    val uncovered = sections.filter { it.title !in UNDRILLED_SECTIONS }.flatMap { section ->
      if (section.subsections.isEmpty()) {
        listOfNotNull("${section.file}: ${section.title}".takeIf { covered.none { (title, _) -> title == section.title } })
      } else {
        section.subsections.filter { (section.title to it) !in covered }.map { "${section.file}: ${section.title} > $it" }
      }
    }
    assertThat(uncovered).isEmpty()
  }

  private fun parse(pointer: String): Pair<String, String?> {
    val parts = pointer.replace(NOTE, "").trim().split(" > ")
    return parts[0] to parts.getOrNull(1)
  }

  private fun sections(file: Path): List<Section> {
    val out = mutableListOf<Section>()
    for (line in Files.readAllLines(file)) {
      when {
        line.startsWith("## ") -> out += Section(file.name, line.removePrefix("## ").trim())
        line.startsWith("### ") -> out.lastOrNull()?.subsections?.add(line.removePrefix("### ").trim())
      }
    }
    return out
  }

  private companion object {
    val NOTE = Regex("""\s*\([^)]*\)$""")
    val UNDRILLED_SECTIONS = emptySet<String>()
  }
}

package com.c0x12c.redlab.labctl

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import com.c0x12c.redlab.labctl.quiz.Quiz
import com.c0x12c.redlab.labctl.scenario.ScenarioCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Every `doc` pointer, written as `Section > Subsection (note)`, must name real headings of the guide. */
class GuidePointersTest {

  private val guide: Path = Path.of("../../..", Quiz.GUIDE)

  @Test
  fun `every scenario and quiz pointer names a heading of the guide`() {
    assertThat(Files.exists(guide)).isTrue()
    val headings = headings(Files.readAllLines(guide))
    val pointers = ScenarioCatalog.all.map { it.id to it.doc } +
      Quiz.generators.mapIndexed { index, generate -> "quiz $index" to generate(Random(1)).doc }
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
  }
}

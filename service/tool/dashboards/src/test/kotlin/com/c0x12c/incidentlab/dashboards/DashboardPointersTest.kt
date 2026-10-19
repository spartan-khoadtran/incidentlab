package com.c0x12c.incidentlab.dashboards

import assertk.assertThat
import assertk.assertions.isEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.junit.jupiter.api.Test

/**
 * Text that sends a learner to a panel names it as `dashboard 02, row 1`, as `row 1 of \`02 · Title\``,
 * as `\`02 · Title\`, row 1`, or by the board alone. Every such pointer must name a board and a row that exist, and no
 * row number may stand without its board.
 */
class DashboardPointersTest {

  private val root = Path.of("../../..")
  private val boards = Boards.all().associateBy { it.number }
  private val titles = boards.values.map { it.title }.toSet()

  @Test
  fun `every board and row pointer names a real board and row`() {
    val problems = sources().flatMap { file -> check(root.relativize(file).invariantSeparatorsPathString, file.readText()) }
    assertThat(problems).isEmpty()
  }

  private fun check(name: String, text: String): List<String> {
    val problems = mutableListOf<String>()
    val pointed = mutableSetOf<IntRange>()
    for (match in NUMBERED.findAll(text)) {
      pointed += match.groups[ROW]!!.range
      problems += rowProblem(name, match.groupValues[BOARD], match.groupValues[ROW].toInt())
    }
    for (match in TITLED.findAll(text)) {
      pointed += match.groups[1]!!.range
      problems += rowProblem(name, match.groupValues[2], match.groupValues[1].toInt())
    }
    for (match in TITLE_THEN_ROW.findAll(text)) {
      pointed += match.groups[2]!!.range
      problems += rowProblem(name, match.groupValues[1], match.groupValues[2].toInt())
    }
    for (match in BOARD_ONLY.findAll(text)) {
      if (match.groupValues[1] !in boards) problems += "$name: dashboard ${match.groupValues[1]} does not exist"
    }
    for (match in TITLE.findAll(text)) {
      if (match.groupValues[1] !in titles) problems += "$name: no board is titled '${match.groupValues[1]}'"
    }
    for (match in BARE_ROW.findAll(text)) {
      if (pointed.none { match.groups[1]!!.range.first in it }) problems += "$name: '${line(text, match.range.first)}' has a row number with no board"
    }
    for (old in RETIRED) {
      old.find(text)?.let { problems += "$name: '${it.value}' names a board that no longer exists" }
    }
    return problems
  }

  private fun rowProblem(name: String, board: String, row: Int): List<String> {
    val target = boards[board] ?: return listOf("$name: dashboard $board does not exist")
    return if (row in 1..target.rows.size) emptyList() else listOf("$name: dashboard $board has no row $row (it has ${target.rows.size})")
  }

  private fun line(text: String, at: Int): String = text.substring(text.lastIndexOf('\n', at) + 1, text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length).trim().take(LINE_PREVIEW)

  private fun sources(): List<Path> {
    val docs = Files.list(root.resolve("docs")).use { files -> files.filter { it.extension == "md" }.toList() }
    val code = listOf("service", "site/src").flatMap { dir ->
      Files.walk(root.resolve(dir)).use { files ->
        files.filter { path ->
          val p = path.invariantSeparatorsPathString
          path.extension in setOf("kt", "ts", "tsx") && "/src/test/" !in p && "/build/" !in p && "/generated/" !in p && "/node_modules/" !in p
        }.toList()
      }
    }
    return docs + code + listOf(root.resolve("README.md"), root.resolve("CLAUDE.md"))
  }

  private companion object {
    const val BOARD = 1
    const val ROW = 2
    const val LINE_PREVIEW = 120
    val NUMBERED = Regex("[Dd]ashboard (\\d{2}), [Rr]ow (\\d+)")
    val TITLED = Regex("[Rr]ow (\\d+) of `(\\d{2}) · [^`]+`")
    val TITLE_THEN_ROW = Regex("`(\\d{2}) · [^`]+`, [Rr]ow (\\d+)")
    val BOARD_ONLY = Regex("[Dd]ashboard (\\d{2})\\b")
    val TITLE = Regex("`(\\d{2} · [^`]+)`")
    val BARE_ROW = Regex("\\b[Rr]ow (\\d+)\\b")
    val RETIRED = listOf("USE by layer", "USE dashboard\\b", "USE board\\b", "use-layers").map { Regex(it, RegexOption.IGNORE_CASE) }
  }
}

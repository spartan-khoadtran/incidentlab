package com.c0x12c.incidentlab.dashboards

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

// Usage: dashboards <output dir>. Writes one JSON file per board in Boards and removes the lab's older files.
object Main {

  @JvmStatic
  fun main(args: Array<String>) {
    val out = Path.of(args.firstOrNull() ?: error("usage: dashboards <output dir>"))
    Files.createDirectories(out)
    val printer = DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
    val writer = ObjectMapper().writer(printer)
    val boards = Boards.all()
    val names = boards.map { it.fileName }.toSet()
    Files.list(out).use { files ->
      files.filter { it.fileName.toString().matches(BOARD_FILE) && it.fileName.toString() !in names }.forEach {
        Files.delete(it)
        println("removed $it")
      }
    }
    for (board in boards) {
      Files.writeString(out.resolve(board.fileName), writer.writeValueAsString(board.toJson()) + "\n")
      println("wrote ${out.resolve(board.fileName)} (${board.panels.size} panels)")
    }
  }

  private val BOARD_FILE = Regex("\\d{2}-[a-z-]+\\.json")
}

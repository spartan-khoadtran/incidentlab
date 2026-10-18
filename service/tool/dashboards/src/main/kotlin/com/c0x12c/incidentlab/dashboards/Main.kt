package com.c0x12c.incidentlab.dashboards

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

// Usage: dashboards <output dir>. Writes 01-red-layers.json and 02-use-layers.json.
object Main {

  @JvmStatic
  fun main(args: Array<String>) {
    val out = Path.of(args.firstOrNull() ?: error("usage: dashboards <output dir>"))
    Files.createDirectories(out)
    val printer = DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
    val writer = ObjectMapper().writer(printer)
    for ((name, board) in listOf("01-red-layers.json" to RedBoard.build(), "02-use-layers.json" to UseBoard.build())) {
      Files.writeString(out.resolve(name), writer.writeValueAsString(board.toJson()) + "\n")
      println("wrote ${out.resolve(name)} (${board.panels.size} panels)")
    }
  }
}

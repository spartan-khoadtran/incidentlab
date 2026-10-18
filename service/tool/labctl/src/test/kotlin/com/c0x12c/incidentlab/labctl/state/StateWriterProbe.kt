package com.c0x12c.incidentlab.labctl.state

import java.nio.file.Files
import java.nio.file.Path

/** Run in a second JVM by [StateStoreTest]: warms up, says it is ready, waits for a line on stdin, then adds 1 to a counter many times. */
object StateWriterProbe {

  @JvmStatic
  fun main(args: Array<String>) {
    val store = StateStore(Path.of(args[0]))
    StateStore(Files.createTempFile("warm-up", ".json")).update { it }
    println("ready")
    System.out.flush()
    readlnOrNull()
    repeat(args[1].toInt()) { StateStoreTest.increment(store) }
    println("done")
  }
}

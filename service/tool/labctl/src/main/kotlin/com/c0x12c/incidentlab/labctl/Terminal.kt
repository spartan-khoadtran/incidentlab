package com.c0x12c.incidentlab.labctl

/** Where a command talks to the learner. The real one is stdin/stdout; tests script the answers. */
interface Terminal {
  fun out(line: String = "")

  /** Null means no interactive input is available. */
  fun ask(prompt: String): String?
}

object ConsoleTerminal : Terminal {
  override fun out(line: String) {
    println(line)
  }

  override fun ask(prompt: String): String? {
    print(prompt)
    System.out.flush()
    return readlnOrNull()
  }
}

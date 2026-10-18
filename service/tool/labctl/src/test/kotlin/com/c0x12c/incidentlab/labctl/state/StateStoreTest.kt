package com.c0x12c.incidentlab.labctl.state

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateStoreTest {

  @Test
  fun `round trips through the file with snake_case keys`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    val state = LabState(
      active = ActiveExercise(id = "pool_hold", params = mapOf("recommend_ms" to 230), started = 1.5, hints = 1, n = 3),
      history = listOf(HistoryEntry(n = 1, id = "oom", title = "Periodic OOM kill", level = 3, score = null, hints = 0, minutes = 4.2, at = 2.0)),
      base = mapOf("loadgen" to mapOf("rate_multiplier" to 1.5))
    )

    store.save(state)

    assertThat(store.load()).isEqualTo(state)
    assertThat(Files.readString(dir.resolve("state.json")).contains("\"rate_multiplier\"")).isEqualTo(true)
  }

  @Test
  fun `a state file written before modes existed reads as incident mode`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")
    Files.writeString(
      path,
      """{"active": {"id": "pool_hold", "params": {"recommend_ms": 230}, "started": 1.5, "n": 3},
         "history": [{"n": 1, "id": "oom", "title": "Periodic OOM kill", "level": 3, "score": null, "hints": 0, "minutes": 4.2, "at": 2.0}]}"""
    )

    val state = StateStore(path).load()

    assertThat(state.active?.mode).isEqualTo("incident")
    assertThat(state.history.map { it.mode }).isEqualTo(listOf("incident"))
    assertThat(state.active?.key).isEqualTo("pool_hold@1.5")
    assertThat(StateStore(path).load().active?.key).isEqualTo(state.active?.key)
    assertThat(state.active?.annotations).isEqualTo(emptyList<PlannedAnnotation>())
  }

  @Test
  fun `a missing or broken file reads as the empty state`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")
    assertThat(StateStore(path).load()).isEqualTo(LabState())

    Files.writeString(path, "{not json")
    assertThat(StateStore(path).load()).isEqualTo(LabState())
  }

  @Test
  fun `two threads that update at once lose no change`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")

    val writers = List(2) { thread { repeat(UPDATES) { increment(StateStore(path)) } } }
    writers.forEach { it.join() }

    assertThat(count(StateStore(path))).isEqualTo(2 * UPDATES)
  }

  @Test
  fun `another process that updates at the same time loses no change`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")
    val java = ProcessHandle.current().info().command().orElse("java")
    val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), StateWriterProbe::class.java.name, path.toString(), UPDATES.toString())
      .redirectErrorStream(true)
      .start()
    val output = child.inputStream.bufferedReader()
    StateStore(dir.resolve("warm-up.json")).update { it }
    assertThat(output.readLine()).isEqualTo("ready")
    child.outputStream.bufferedWriter().apply {
      write("go\n")
      flush()
    }

    repeat(UPDATES) { increment(StateStore(path)) }

    assertThat(child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(output.readText().trim()).isEqualTo("done")
    assertThat(count(StateStore(path))).isEqualTo(2 * UPDATES)
  }

  @Test
  fun `a lock taken inside a lock on the same file does not block`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))

    store.withLock { _, _ -> store.update { it.copy(base = mapOf("loadgen" to mapOf("rate_multiplier" to 2.0))) } }

    assertThat(store.load().base.getValue("loadgen")["rate_multiplier"]).isEqualTo(2.0)
  }

  companion object {
    private const val UPDATES = 200
    private const val TIMEOUT_SECONDS = 60L

    fun increment(store: StateStore) {
      store.update { state ->
        val next = (state.base["counter"]?.get("n") as? Number)?.toInt()?.plus(1) ?: 1
        state.copy(base = state.base + ("counter" to mapOf("n" to next)))
      }
    }

    private fun count(store: StateStore): Int = (store.load().base.getValue("counter")["n"] as Number).toInt()
  }
}

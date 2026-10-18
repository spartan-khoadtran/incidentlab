package com.c0x12c.incidentlab.labctl.state

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
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
  }

  @Test
  fun `a missing or broken file reads as the empty state`(@TempDir dir: Path) {
    val path = dir.resolve("state.json")
    assertThat(StateStore(path).load()).isEqualTo(LabState())

    Files.writeString(path, "{not json")
    assertThat(StateStore(path).load()).isEqualTo(LabState())
  }
}

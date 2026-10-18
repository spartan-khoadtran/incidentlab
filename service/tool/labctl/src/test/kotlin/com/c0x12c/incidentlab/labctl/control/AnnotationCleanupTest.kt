package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.c0x12c.incidentlab.labctl.FakeAnnotator
import com.c0x12c.incidentlab.labctl.FakePrometheus
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.Outcome
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.PlannedAnnotation
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.ZoneId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AnnotationCleanupTest {

  private var clock = NOW
  private val annotator = FakeAnnotator()
  private val reports = mutableListOf<String>()

  private fun store(dir: Path) = StateStore(dir.resolve("state.json"))

  private fun cleanup(dir: Path) = AnnotationCleanup(store(dir), annotator, { clock }) { reports += it }

  private fun ramp(vararg marks: PlannedAnnotation) =
    ActiveExercise(id = "ramp", mode = "ramp", params = mapOf("every" to 60), started = NOW - 100, n = 1, key = "k1", annotations = marks.toList())

  @Test
  fun `an early clear queues the future marks in the same write and calls no delete`(@TempDir dir: Path) {
    store(dir).save(LabState(active = ramp(PlannedAnnotation(1, NOW - 100), PlannedAnnotation(2, NOW + 20), PlannedAnnotation(3, NOW + 80))))
    val engine = ExerciseEngine(store(dir), FakePrometheus { _, _ -> null }, annotator, now = { clock }, zone = ZoneId.of("UTC"))

    assertThat(engine.clear()).isEqualTo(Outcome.Done(true))

    assertThat(annotator.attempts).isEmpty()
    val state = store(dir).load()
    assertThat(state.active).isNull()
    assertThat(state.pendingAnnotationDeletes).containsExactly(PlannedAnnotation(2, NOW + 20), PlannedAnnotation(3, NOW + 80))
  }

  @Test
  fun `one pass deletes every pending mark and empties the queue`(@TempDir dir: Path) {
    store(dir).save(LabState(pendingAnnotationDeletes = listOf(PlannedAnnotation(4, NOW + 20), PlannedAnnotation(5, NOW + 80))))

    cleanup(dir).runOnce()

    assertThat(annotator.deleted).containsExactly(4L, 5L)
    assertThat(store(dir).load().pendingAnnotationDeletes).isEmpty()
    assertThat(reports).isEmpty()
  }

  @Test
  fun `a failed delete stays pending, is tried again next pass and is reported once`(@TempDir dir: Path) {
    store(dir).save(LabState(pendingAnnotationDeletes = listOf(PlannedAnnotation(4, NOW + 20))))
    val cleanup = cleanup(dir)
    annotator.down = true

    repeat(3) { cleanup.runOnce() }

    assertThat(annotator.attempts).containsExactly(4L, 4L, 4L)
    assertThat(store(dir).load().pendingAnnotationDeletes).containsExactly(PlannedAnnotation(4, NOW + 20))
    assertThat(reports).isEqualTo(listOf("could not delete annotation 4 from Grafana, will retry: connection refused"))

    annotator.down = false
    cleanup.runOnce()

    assertThat(annotator.deleted).containsExactly(4L)
    assertThat(store(dir).load().pendingAnnotationDeletes).isEmpty()
  }

  @Test
  fun `a mark that keeps failing an hour past its time is dropped`(@TempDir dir: Path) {
    store(dir).save(LabState(pendingAnnotationDeletes = listOf(PlannedAnnotation(4, NOW + 20), PlannedAnnotation(5, NOW + 900))))
    annotator.down = true
    clock = NOW + 20 + AnnotationCleanup.GIVE_UP_AFTER_SECONDS + 1

    cleanup(dir).runOnce()

    assertThat(store(dir).load().pendingAnnotationDeletes).containsExactly(PlannedAnnotation(5, NOW + 900))
    assertThat(reports.first()).isEqualTo("gave up deleting annotation 4 from Grafana: connection refused")
  }

  @Test
  fun `a mark queued while a pass runs is kept for the next pass`(@TempDir dir: Path) {
    store(dir).save(LabState(pendingAnnotationDeletes = listOf(PlannedAnnotation(4, NOW + 20))))
    val racing = object : Annotator by annotator {
      override fun delete(id: Long): Deletion {
        store(dir).update { it.copy(pendingAnnotationDeletes = it.pendingAnnotationDeletes + PlannedAnnotation(9, NOW + 30)) }
        return annotator.delete(id)
      }
    }

    AnnotationCleanup(store(dir), racing, { clock }) { reports += it }.runOnce()

    assertThat(store(dir).load().pendingAnnotationDeletes).containsExactly(PlannedAnnotation(9, NOW + 30))
  }

  @Test
  fun `marks written after their exercise ended are queued, not kept on the next exercise`(@TempDir dir: Path) {
    store(dir).save(LabState(active = ramp()))
    val clearing = object : Annotator by annotator {
      override fun annotate(text: String, tags: List<String>, atSeconds: Double): Long {
        store(dir).update { it.copy(active = null) }
        return annotator.annotate(text, tags, atSeconds)
      }
    }

    ScheduledAnnotations.write(store(dir), clearing, "k1", listOf("ramp"), listOf("step 1" to NOW - 10, "step 2" to NOW + 50, "over" to NOW + 110)) { clock }

    assertThat(annotator.attempts).isEmpty()
    assertThat(store(dir).load().pendingAnnotationDeletes).containsExactly(PlannedAnnotation(2, NOW + 50), PlannedAnnotation(3, NOW + 110))
  }

  @Test
  fun `Grafana's 404 counts as deleted, any other error does not`() {
    val status = mapOf("/api/annotations/1" to 200, "/api/annotations/2" to 404, "/api/annotations/3" to 500)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        exchange.sendResponseHeaders(status.getValue(exchange.requestURI.path), -1)
        exchange.close()
      }
      start()
    }
    try {
      val grafana = GrafanaAnnotator(JsonHttp(), "http://127.0.0.1:${server.address.port}") {}

      assertThat(grafana.delete(1)).isEqualTo(Deletion.Gone)
      assertThat(grafana.delete(2)).isEqualTo(Deletion.Gone)
      assertThat(grafana.delete(3)).isInstanceOf(Deletion.Failed::class)
    } finally {
      server.stop(0)
    }
  }

  private companion object {
    const val NOW = 1_700_000_000.0
  }
}

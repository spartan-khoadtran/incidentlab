package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.PlannedAnnotation
import com.c0x12c.incidentlab.labctl.state.StateStore

/**
 * The marks a ramp or a bench writes at its start for times still to come. Their ids stay in the
 * exercise's state, so an exercise that ends early takes back the marks it will never reach.
 */
object ScheduledAnnotations {

  /** Writes [marks] (text and time) for the exercise named [key]. If it ended in the meantime, the future ones are deleted at once. */
  fun write(store: StateStore, annotator: Annotator, key: String, tags: List<String>, marks: List<Pair<String, Double>>, now: () -> Double) {
    val written = marks.mapNotNull { (text, at) -> annotator.annotate(text, tags, at)?.let { PlannedAnnotation(it, at) } }
    val kept = store.withLock { state, write ->
      val active = state.active
      if (active?.key == key) {
        write(state.copy(active = active.copy(annotations = active.annotations + written)))
        true
      } else {
        false
      }
    }
    if (!kept) deleteFuture(annotator, written, now())
  }

  /** Deletes the marks of an exercise that just ended whose time has not come yet. */
  fun dropFuture(annotator: Annotator, ended: ActiveExercise?, now: Double) {
    ended?.let { deleteFuture(annotator, it.annotations, now) }
  }

  private fun deleteFuture(annotator: Annotator, annotations: List<PlannedAnnotation>, now: Double) {
    annotations.filter { it.at > now }.forEach { annotator.delete(it.id) }
  }
}

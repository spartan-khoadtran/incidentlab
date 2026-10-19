package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.state.PlannedAnnotation
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.incidentlab.labctl.state.queueMarks

/**
 * The marks a ramp or a bench writes at its start for times still to come. Their ids stay in the
 * exercise's state, so an exercise that ends early queues the marks it will never reach, and the
 * control daemon deletes them ([AnnotationCleanup]).
 */
object ScheduledAnnotations {

  /** Writes [marks] (text and time) for the exercise named [key]. If it ended in the meantime, the future ones are queued for deletion. */
  fun write(store: StateStore, annotator: Annotator, key: String, tags: List<String>, marks: List<Pair<String, Double>>, now: () -> Double) {
    val written = marks.mapNotNull { (text, at) -> annotator.annotate(text, tags, at)?.let { PlannedAnnotation(it, at) } }
    if (written.isEmpty()) return
    store.withLock { state, write ->
      val active = state.active
      if (active?.key == key) {
        write(state.copy(active = active.copy(annotations = active.annotations + written)))
      } else {
        val at = now()
        val future = written.filter { it.at > at }
        if (future.isNotEmpty()) write(state.queueMarks(future))
      }
    }
  }
}

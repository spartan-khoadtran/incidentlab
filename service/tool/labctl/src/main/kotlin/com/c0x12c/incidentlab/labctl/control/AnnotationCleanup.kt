package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.state.PlannedAnnotation
import com.c0x12c.incidentlab.labctl.state.StateStore

/**
 * Deletes from Grafana the marks of exercises that ended early (`LabState.pendingAnnotationDeletes`).
 * Ending an exercise only queues them, so a slow or down Grafana never holds up a command or a
 * request, and no id is lost: a failed delete stays queued and is tried again next pass. A mark
 * more than [giveUpAfterSeconds] past its own time is dropped, so the queue cannot grow for ever.
 */
class AnnotationCleanup(
  private val store: StateStore,
  private val annotator: Annotator,
  private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
  private val giveUpAfterSeconds: Double = GIVE_UP_AFTER_SECONDS,
  private val report: (String) -> Unit
) {

  /** Ids whose failure was already reported, so a Grafana that stays down logs once per mark, not once per pass. */
  private val reported = mutableSetOf<Long>()

  fun runOnce() {
    val pending = store.load().pendingAnnotationDeletes
    reported.retainAll(pending.map { it.id }.toSet())
    if (pending.isEmpty()) return
    val at = now()
    val settled = pending.filter { settle(it, annotator.delete(it.id), at) }.map { it.id }.toSet()
    if (settled.isEmpty()) return
    store.update { state -> state.copy(pendingAnnotationDeletes = state.pendingAnnotationDeletes.filter { it.id !in settled }) }
    reported -= settled
  }

  /** True when the mark leaves the queue: deleted, already gone, or too old to keep trying. */
  private fun settle(mark: PlannedAnnotation, deletion: Deletion, at: Double): Boolean =
    when (deletion) {
      Deletion.Gone -> true
      is Deletion.Failed -> {
        val tooOld = at - mark.at > giveUpAfterSeconds
        if (tooOld) {
          report("gave up deleting annotation ${mark.id} from Grafana: ${deletion.reason}")
        } else if (reported.add(mark.id)) {
          report("could not delete annotation ${mark.id} from Grafana, will retry: ${deletion.reason}")
        }
        tooOld
      }
    }

  companion object {
    const val GIVE_UP_AFTER_SECONDS = 3_600.0
    private const val MILLIS_PER_SECOND = 1_000.0
  }
}

package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.concepts.BenchmarkingConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.FoundationsConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.InvestigationConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.MethodsConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.MonitoringConcepts

/** Every concept drill, one list per guide file. */
object Concepts {
  val all: List<ChoiceQuestion> = listOf(
    InvestigationConcepts.all,
    FoundationsConcepts.all,
    MonitoringConcepts.all,
    MethodsConcepts.all,
    BenchmarkingConcepts.all
  ).flatten()
}

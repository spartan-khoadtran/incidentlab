package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.concepts.BenchmarkingConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.CapacityConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.CpuConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.FoundationsConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.InvestigationConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.MethodsConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.MonitoringConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.ProfilingConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.ToolboxConcepts

/** Every concept drill, one list per guide file. */
object Concepts {
  val all: List<ChoiceQuestion> = listOf(
    InvestigationConcepts.all,
    BenchmarkingConcepts.all,
    CapacityConcepts.all,
    CpuConcepts.all,
    FoundationsConcepts.all,
    MethodsConcepts.all,
    MonitoringConcepts.all,
    ProfilingConcepts.all,
    ToolboxConcepts.all
  ).flatten()
}

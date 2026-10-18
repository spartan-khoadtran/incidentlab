package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.concepts.InvestigationConcepts
import com.c0x12c.incidentlab.labctl.quiz.concepts.MethodsConcepts

/** Every concept drill, one list per guide file. */
object Concepts {
  val all: List<ChoiceQuestion> = InvestigationConcepts.all + MethodsConcepts.all
}

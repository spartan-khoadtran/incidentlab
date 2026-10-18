package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.numbers.FoundationsNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.InvestigationNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.MonitoringNumbers
import kotlin.random.Random

/** Every numeric drill, one list per guide file. */
object Numbers {
  val generators: List<(Random) -> NumericQuestion> = InvestigationNumbers.generators + FoundationsNumbers.generators + MonitoringNumbers.generators
}

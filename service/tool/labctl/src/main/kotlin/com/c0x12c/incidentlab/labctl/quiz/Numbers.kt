package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.numbers.BenchmarkingNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.FoundationsNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.InvestigationNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.MethodsNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.MonitoringNumbers
import kotlin.random.Random

/** Every numeric drill, one list per guide file. */
object Numbers {
  val generators: List<(Random) -> NumericQuestion> = listOf(
    InvestigationNumbers.generators,
    FoundationsNumbers.generators,
    MonitoringNumbers.generators,
    MethodsNumbers.generators,
    BenchmarkingNumbers.generators
  ).flatten()
}

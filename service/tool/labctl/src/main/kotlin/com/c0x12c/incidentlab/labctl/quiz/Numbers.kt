package com.c0x12c.incidentlab.labctl.quiz

import com.c0x12c.incidentlab.labctl.quiz.numbers.BenchmarkingNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.CapacityNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.CpuNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.DiskNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.FoundationsNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.InvestigationNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.MethodsNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.MonitoringNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.NetworkNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.ProfilingNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.RuntimeNumbers
import com.c0x12c.incidentlab.labctl.quiz.numbers.ToolboxNumbers
import kotlin.random.Random

/** Every numeric drill, one list per guide file. */
object Numbers {
  val generators: List<(Random) -> NumericQuestion> = listOf(
    InvestigationNumbers.generators,
    BenchmarkingNumbers.generators,
    CapacityNumbers.generators,
    CpuNumbers.generators,
    DiskNumbers.generators,
    FoundationsNumbers.generators,
    MethodsNumbers.generators,
    MonitoringNumbers.generators,
    NetworkNumbers.generators,
    ProfilingNumbers.generators,
    RuntimeNumbers.generators,
    ToolboxNumbers.generators
  ).flatten()
}

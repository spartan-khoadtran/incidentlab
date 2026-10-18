package com.c0x12c.incidentlab.labctl.export

import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.bench.BenchExercise
import com.c0x12c.incidentlab.labctl.control.ControlDaemon
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Concepts
import com.c0x12c.incidentlab.labctl.quiz.Drill
import com.c0x12c.incidentlab.labctl.quiz.Numbers
import com.c0x12c.incidentlab.labctl.ramp.RampExercise
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/**
 * What the handbook site may show about the catalog and the quiz. It holds ids, titles, levels,
 * topics and guide pointers, and counts. It never holds an answer, a ticket, a hint or a proof.
 */
object LabExport {

  data class ScenarioCard(
    val id: String,
    val title: String,
    val level: Int,
    val topics: List<String>,
    val doc: String,
    val docFile: String?,
    val toolbox: Boolean
  )

  data class ModeCard(val id: String, val doc: String, val docFile: String?)

  data class QuizFile(val file: String, val numbers: Int, val concepts: Int)

  data class Scoring(
    val naturePoints: Double,
    val locationPoints: Double,
    val signalPoints: Double,
    val causePoints: Double,
    val partialCausePoints: Double,
    val hintPenalty: Double,
    val maxPoints: Double
  )

  data class Counts(
    val scenarios: Int,
    val toolbox: Int,
    val causes: Int,
    val signals: Int,
    val numbers: Int,
    val concepts: Int
  )

  data class Export(
    val scenarios: List<ScenarioCard>,
    val topics: List<String>,
    val levels: List<Int>,
    val modes: List<ModeCard>,
    val quiz: List<QuizFile>,
    val scoring: Scoring,
    val tickSeconds: Long,
    val recentWindow: Int,
    val counts: Counts
  )

  private const val TOOLBOX_DOC = "The toolbox in this lab > make toolbox and make shell"
  private const val INCIDENT_DOC = "In this lab"
  private val NOTE = Regex("""\s*\([^)]*\)$""")

  fun build(): Export {
    val scenarios = ScenarioCatalog.all.map {
      ScenarioCard(it.id, it.title, it.level, it.topics, it.doc, fileOf(it.doc), it.cli.isNotEmpty())
    }
    val numbers = Numbers.generators.map { it(Random(1)) }
    val concepts = Concepts.all
    return Export(
      scenarios = scenarios,
      topics = Answers.TOPICS,
      levels = scenarios.map { it.level }.distinct().sorted(),
      modes = listOf(
        Exercises.INCIDENT to INCIDENT_DOC,
        Exercises.TOOLBOX to TOOLBOX_DOC,
        Exercises.RAMP to RampExercise.DOC,
        Exercises.BENCH to BenchExercise.DOC
      ).map { (id, doc) -> ModeCard(id, doc, fileOf(doc)) },
      quiz = quizFiles(numbers, concepts),
      scoring = Scoring(
        naturePoints = Exercises.NATURE_POINTS,
        locationPoints = Exercises.LOCATION_POINTS,
        signalPoints = Exercises.SIGNAL_POINTS,
        causePoints = Exercises.CAUSE_POINTS,
        partialCausePoints = Exercises.PARTIAL_POINTS,
        hintPenalty = Exercises.HINT_PENALTY,
        maxPoints = Exercises.MAX_POINTS
      ),
      tickSeconds = ControlDaemon.TICK.seconds,
      recentWindow = Exercises.RECENT_WINDOW,
      counts = Counts(
        scenarios = scenarios.size,
        toolbox = scenarios.count { it.toolbox },
        causes = Answers.CAUSES.size,
        signals = Answers.SIGNALS.size,
        numbers = numbers.size,
        concepts = concepts.size
      )
    )
  }

  fun write(out: Path) {
    out.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val tmp = Files.createTempFile(out.toAbsolutePath().parent, out.fileName.toString(), ".tmp")
    Files.writeString(tmp, LabJson.mapper.writeValueAsString(build()) + "\n")
    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  /** A drill counts for the file of the first section its pointer names, so a new docs file needs no change here. */
  private fun quizFiles(numbers: List<Drill>, concepts: List<Drill>): List<QuizFile> {
    val numbersByFile = numbers.groupingBy { fileOf(it.doc) }.eachCount()
    val conceptsByFile = concepts.groupingBy { fileOf(it.doc) }.eachCount()
    return (numbersByFile.keys + conceptsByFile.keys).filterNotNull().sorted().map {
      QuizFile(it, numbersByFile[it] ?: 0, conceptsByFile[it] ?: 0)
    }
  }

  private fun fileOf(doc: String): String? = GuideIndex.fileOf[doc.split("; ").first().replace(NOTE, "").substringBefore(" > ").trim()]
}

package com.c0x12c.incidentlab.labctl.export

import com.c0x12c.incidentlab.labctl.LabJson
import com.c0x12c.incidentlab.labctl.Main
import com.c0x12c.incidentlab.labctl.bench.BenchCatalog
import com.c0x12c.incidentlab.labctl.bench.BenchExercise
import com.c0x12c.incidentlab.labctl.control.ControlDaemon
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.guide.GuideIndex
import com.c0x12c.incidentlab.labctl.quiz.Concepts
import com.c0x12c.incidentlab.labctl.quiz.Drill
import com.c0x12c.incidentlab.labctl.quiz.Numbers
import com.c0x12c.incidentlab.labctl.ramp.RampExercise
import com.c0x12c.incidentlab.labctl.ramp.RampPlan
import com.c0x12c.incidentlab.labctl.scenario.Answers
import com.c0x12c.incidentlab.labctl.scenario.Baseline
import com.c0x12c.incidentlab.labctl.scenario.ScenarioCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/**
 * What the handbook site may show about the catalog and the quiz: what `make list` prints, and
 * counts. A scenario's guide pointer stays out, because it names the cause and the CLI only prints
 * it after the answer. It never holds an answer, a ticket, a hint or a proof.
 */
object LabExport {

  data class ScenarioCard(
    val id: String,
    val title: String,
    val level: Int,
    val topics: List<String>,
    val toolbox: Boolean
  )

  data class ModeCard(val id: String, val guide: String, val guideFile: String?)

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

  data class Defaults(
    val baseRps: Int,
    val rampRates: List<Int>,
    val rampStepSeconds: Int,
    val benchClients: Int,
    val benchSeconds: Int,
    val quizQuestions: Int
  )

  data class Range(val min: Double, val max: Double)

  /** The bounds `CommandLine` accepts, so a slider on the site offers only values the lab takes. */
  data class Limits(
    val loadMultiplier: Range,
    val rampStepSeconds: Range,
    val benchClients: Range,
    val benchSeconds: Range
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
    val defaults: Defaults,
    val limits: Limits,
    val counts: Counts
  )

  private const val TOOLBOX_DOC = "The toolbox in this lab > make toolbox and make shell"
  private const val INCIDENT_DOC = "In this lab"
  private val NOTE = Regex("""\s*\([^)]*\)$""")

  fun build(): Export {
    val scenarios = ScenarioCatalog.all.map {
      ScenarioCard(it.id, it.title, it.level, it.topics, it.cli.isNotEmpty())
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
      defaults = Defaults(
        baseRps = Baseline.BASE_RPS,
        rampRates = RampPlan.rates,
        rampStepSeconds = RampPlan.DEFAULT_STEP_SECONDS,
        benchClients = BenchCatalog.DEFAULT_CLIENTS,
        benchSeconds = BenchCatalog.DEFAULT_SECONDS,
        quizQuestions = Main.DEFAULT_QUIZ_QUESTIONS
      ),
      limits = Limits(
        loadMultiplier = Range(Exercises.MIN_LOAD, Exercises.MAX_LOAD),
        rampStepSeconds = Range(RampPlan.MIN_STEP_SECONDS.toDouble(), RampPlan.MAX_STEP_SECONDS.toDouble()),
        benchClients = Range(BenchCatalog.MIN_CLIENTS.toDouble(), BenchCatalog.MAX_CLIENTS.toDouble()),
        benchSeconds = Range(BenchCatalog.MIN_SECONDS.toDouble(), BenchCatalog.MAX_SECONDS.toDouble())
      ),
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

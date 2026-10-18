package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.api.ApiServer
import com.c0x12c.incidentlab.labctl.api.LabApi
import com.c0x12c.incidentlab.labctl.bench.BenchEngine
import com.c0x12c.incidentlab.labctl.bench.BenchExercise
import com.c0x12c.incidentlab.labctl.control.Annotator
import com.c0x12c.incidentlab.labctl.control.CgroupApplier
import com.c0x12c.incidentlab.labctl.control.ContainerCpuApplier
import com.c0x12c.incidentlab.labctl.control.ControlDaemon
import com.c0x12c.incidentlab.labctl.control.DockerControl
import com.c0x12c.incidentlab.labctl.control.GrafanaAnnotator
import com.c0x12c.incidentlab.labctl.control.HttpLabProbe
import com.c0x12c.incidentlab.labctl.control.JsonHttp
import com.c0x12c.incidentlab.labctl.control.LabProbe
import com.c0x12c.incidentlab.labctl.control.NetemApplier
import com.c0x12c.incidentlab.labctl.control.ServiceFaultApplier
import com.c0x12c.incidentlab.labctl.exercise.ExerciseEngine
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.export.LabExport
import com.c0x12c.incidentlab.labctl.quiz.Quiz
import com.c0x12c.incidentlab.labctl.quiz.QuizSessions
import com.c0x12c.incidentlab.labctl.ramp.RampEngine
import com.c0x12c.incidentlab.labctl.ramp.RampExercise
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import com.c0x12c.logging.warn
import java.nio.file.Path
import kotlin.system.exitProcess

// Lab controller.
//   labctl daemon            keeps every service in the desired fault state and serves the lab API (runs in the control container)
//   labctl new|hint|answer|reveal|clear|status|list|history|load|quiz
//   labctl ramp              a capacity ramp on the live lab (its own mode of make answer)
//   labctl bench             closed-loop clients for a while, then a before/after report
//   labctl export OUT.json   the catalog and quiz counts the handbook site shows, with no answer in it
object Main : Logging {

  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
  }

  fun run(args: List<String>): Int {
    val command = args.firstOrNull() ?: return usage()
    val store = StateStore(Path.of(System.getenv("LAB_STATE") ?: "/state/state.json"))
    val http = JsonHttp()
    val terminal = ConsoleTerminal
    val probe = HttpLabProbe(http)
    val annotator = GrafanaAnnotator(http) { terminal.out(it) }
    val ramp = RampExercise(store, probe, annotator, terminal)
    val bench = BenchExercise(store, probe, annotator, terminal)
    val exercises = Exercises(store, probe, annotator, terminal, modes = listOf(ramp, bench))
    return try {
      when (command) {
        "daemon" -> {
          val docker = DockerControl.ifAvailable(System.getenv("COMPOSE_PROJECT") ?: "incidentlab", Path.of(System.getenv("DOCKER_SOCK") ?: "/var/run/docker.sock"))
          if (docker == null) {
            info { "docker socket not found: CPU-limit, network and disk IOPS exercises are disabled" }
          }
          val cgroups = docker?.let { CgroupApplier.ifAvailable(it::containerId, hostPath("CGROUP_ROOT", "/host/cgroup"), hostPath("HOST_VOLUMES", "/host/volumes"), hostPath("HOST_SYS", "/host/sys")) }
          val appliers = listOfNotNull(ServiceFaultApplier(http), docker?.let { ContainerCpuApplier(it) }, docker?.let { NetemApplier(it) }, cgroups)
          val port = System.getenv("LAB_API_PORT")?.toIntOrNull() ?: ApiServer.DEFAULT_PORT
          labApi(store, probe, GrafanaAnnotator(http) { warn { it } }).start(port)
          info { "lab API listening on port $port" }
          ControlDaemon(store, appliers).run()
          0
        }
        "new" -> exercises.newExercise(CommandLine.newOptions(args.drop(1)))
        "hint" -> exercises.hint()
        "answer" -> exercises.answer()
        "reveal" -> exercises.reveal()
        "clear" -> exercises.clear()
        "status" -> exercises.status()
        "list" -> exercises.list()
        "history" -> exercises.history()
        "load" -> exercises.load(CommandLine.loadMultiplier(args.getOrNull(1)))
        "ramp" -> ramp.start(CommandLine.rampOptions(args.drop(1)))
        "bench" -> bench.start(CommandLine.benchOptions(args.drop(1)))
        "export" -> {
          LabExport.write(Path.of(args.getOrNull(1) ?: return usage()))
          0
        }
        "quiz" -> {
          val options = CommandLine.quizOptions(args.drop(1), DEFAULT_QUIZ_QUESTIONS)
          terminal.out("Drills from the guide. A number question takes a number (a comma or a dot both work as the decimal separator). A concept question takes the number of one option.")
          Quiz.run(options.count, options.kind, terminal)
          0
        }
        else -> usage()
      }
    } catch (e: UsageError) {
      terminal.out(e.message.orEmpty())
      USAGE_EXIT
    }
  }

  fun labApi(store: StateStore, probe: LabProbe, annotator: Annotator): LabApi {
    val ramp = RampEngine(store, probe, annotator)
    val bench = BenchEngine(store, probe, annotator)
    return LabApi(ExerciseEngine(store, probe, annotator, modes = listOf(ramp, bench)), ramp, bench, QuizSessions())
  }

  private fun hostPath(variable: String, default: String): Path = Path.of(System.getenv(variable) ?: default)

  private fun usage(): Int {
    println(
      "usage: labctl daemon | new [--level N] [--topic T] [--id ID] [--force] [--mode incident|toolbox] | hint | answer | reveal | clear | status | list | history | load X | " +
        "quiz [-n N] [--kind numbers|concepts|all] | ramp [--every S] | bench [--clients N] [--seconds S] | export OUT.json"
    )
    return USAGE_EXIT
  }

  private const val USAGE_EXIT = 2
  const val DEFAULT_QUIZ_QUESTIONS = 50
}

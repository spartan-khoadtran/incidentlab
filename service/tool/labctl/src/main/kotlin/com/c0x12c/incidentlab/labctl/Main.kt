package com.c0x12c.incidentlab.labctl

import com.c0x12c.incidentlab.labctl.control.ContainerCpuApplier
import com.c0x12c.incidentlab.labctl.control.ControlDaemon
import com.c0x12c.incidentlab.labctl.control.DockerControl
import com.c0x12c.incidentlab.labctl.control.GrafanaAnnotator
import com.c0x12c.incidentlab.labctl.control.HttpLabProbe
import com.c0x12c.incidentlab.labctl.control.JsonHttp
import com.c0x12c.incidentlab.labctl.control.ServiceFaultApplier
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.quiz.Quiz
import com.c0x12c.incidentlab.labctl.ramp.RampExercise
import com.c0x12c.incidentlab.labctl.state.StateStore
import com.c0x12c.logging.Logging
import com.c0x12c.logging.info
import java.nio.file.Path
import kotlin.system.exitProcess

// Lab controller.
//   labctl daemon            keeps every service in the desired fault state (runs in the control container)
//   labctl new|hint|answer|reveal|clear|status|list|history|load|quiz
//   labctl ramp              a capacity ramp on the live lab (its own mode of make answer)
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
    val annotator = GrafanaAnnotator(http, terminal)
    val ramp = RampExercise(store, probe, annotator, terminal)
    val exercises = Exercises(store, probe, annotator, terminal, modes = listOf(ramp))
    return try {
      when (command) {
        "daemon" -> {
          val docker = DockerControl.ifAvailable(System.getenv("COMPOSE_PROJECT") ?: "incidentlab", Path.of(System.getenv("DOCKER_SOCK") ?: "/var/run/docker.sock"))
          if (docker == null) {
            info { "docker socket not found: CPU-limit exercises are disabled" }
          }
          val appliers = listOfNotNull(ServiceFaultApplier(http), docker?.let { ContainerCpuApplier(it) })
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

  private fun usage(): Int {
    println(
      "usage: labctl daemon | new [--level N] [--topic T] [--id ID] [--force] | hint | answer | reveal | clear | status | list | history | load X | " +
        "quiz [-n N] [--kind numbers|concepts|all] | ramp [--every S]"
    )
    return USAGE_EXIT
  }

  private const val USAGE_EXIT = 2
  private const val DEFAULT_QUIZ_QUESTIONS = 50
}

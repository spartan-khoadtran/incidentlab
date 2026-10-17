package com.c0x12c.redlab.labctl

import com.c0x12c.redlab.labctl.control.ControlDaemon
import com.c0x12c.redlab.labctl.control.DockerControl
import com.c0x12c.redlab.labctl.control.GrafanaAnnotator
import com.c0x12c.redlab.labctl.control.HttpLabProbe
import com.c0x12c.redlab.labctl.control.JsonHttp
import com.c0x12c.redlab.labctl.exercise.Exercises
import com.c0x12c.redlab.labctl.quiz.Quiz
import com.c0x12c.redlab.labctl.state.StateStore
import java.nio.file.Path
import kotlin.system.exitProcess

// Lab controller.
//   labctl daemon            keeps every service in the desired fault state (runs in the control container)
//   labctl new|hint|answer|reveal|clear|status|list|history|load|quiz
object Main {

  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
  }

  fun run(args: List<String>): Int {
    val command = args.firstOrNull() ?: return usage()
    val store = StateStore(Path.of(System.getenv("LAB_STATE") ?: "/state/state.json"))
    val http = JsonHttp()
    val terminal = ConsoleTerminal
    val exercises = Exercises(store, HttpLabProbe(http), GrafanaAnnotator(http, terminal), terminal)
    return try {
      when (command) {
        "daemon" -> {
          val docker = DockerControl.ifAvailable(System.getenv("COMPOSE_PROJECT") ?: "redlab", Path.of(System.getenv("DOCKER_SOCK") ?: "/var/run/docker.sock"))
          ControlDaemon(store, http, docker).run()
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
    println("usage: labctl daemon | new [--level N] [--topic T] [--id ID] [--force] | hint | answer | reveal | clear | status | list | history | load X | quiz [-n N] [--kind numbers|concepts|all]")
    return USAGE_EXIT
  }

  private const val USAGE_EXIT = 2
  private const val DEFAULT_QUIZ_QUESTIONS = 20
}

package com.c0x12c.incidentlab.labctl

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.c0x12c.incidentlab.labctl.bench.BenchOptions
import com.c0x12c.incidentlab.labctl.exercise.Exercises
import com.c0x12c.incidentlab.labctl.exercise.NewOptions
import com.c0x12c.incidentlab.labctl.quiz.QuizKind
import com.c0x12c.incidentlab.labctl.quiz.QuizSessions
import com.c0x12c.incidentlab.labctl.ramp.RampOptions
import org.junit.jupiter.api.Test

class CommandLineTest {

  @Test
  fun `new options parse every flag the Makefile passes`() {
    val options = CommandLine.newOptions(listOf("--level", "2", "--topic", "async", "--id", "pool_hold", "--force"))

    assertThat(options).isEqualTo(NewOptions(level = 2, topic = "async", id = "pool_hold", force = true))
  }

  @Test
  fun `the mode flag takes incident or toolbox and nothing else`() {
    assertThat(CommandLine.newOptions(listOf("--mode", "toolbox", "--level", "2"))).isEqualTo(NewOptions(level = 2, mode = Exercises.TOOLBOX))
    assertThat(CommandLine.newOptions(emptyList()).mode).isEqualTo(Exercises.INCIDENT)
    assertFailure { CommandLine.newOptions(listOf("--mode", "grafana")) }.hasMessage("MODE must be one of incident, toolbox.")
    assertFailure { CommandLine.newOptions(listOf("--mode")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `a level that is not 1 to 3 is refused`() {
    assertFailure { CommandLine.newOptions(listOf("--level", "abc")) }.hasMessage("LEVEL must be one of 1, 2, 3. See `make list`.")
    assertFailure { CommandLine.newOptions(listOf("--level", "9")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `an unknown topic is refused with the topic list`() {
    assertFailure { CommandLine.newOptions(listOf("--topic", "nope")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `load takes a number in range, with a dot or a comma`() {
    assertThat(CommandLine.loadMultiplier("1.5")).isEqualTo(1.5)
    assertThat(CommandLine.loadMultiplier("1,5")).isEqualTo(1.5)
    assertFailure { CommandLine.loadMultiplier("abc") }.isInstanceOf(UsageError::class)
    assertFailure { CommandLine.loadMultiplier("9") }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `quiz takes a positive count and a kind, and falls back to the given count of mixed questions`() {
    assertThat(CommandLine.quizOptions(listOf("-n", "3", "--kind", "concepts"), 5)).isEqualTo(QuizOptions(count = 3, kind = QuizKind.CONCEPTS))
    assertThat(CommandLine.quizOptions(emptyList(), 5)).isEqualTo(QuizOptions(count = 5, kind = QuizKind.ALL))
    assertFailure { CommandLine.quizOptions(listOf("-n", "x"), 5) }.isInstanceOf(UsageError::class)
    assertFailure { CommandLine.quizOptions(listOf("--kind", "riddles"), 5) }.hasMessage("KIND must be one of numbers, concepts, all, for example `make quiz KIND=concepts`.")
    assertFailure { CommandLine.quizOptions(listOf("--bogus"), 5) }.isInstanceOf(UsageError::class)
    assertThat(QuizSessions.DEFAULT_COUNT).isEqualTo(50)
  }

  @Test
  fun `ramp takes a step length in seconds within bounds`() {
    assertThat(CommandLine.rampOptions(emptyList())).isEqualTo(RampOptions(every = 60))
    assertThat(CommandLine.rampOptions(listOf("--every", "45"))).isEqualTo(RampOptions(every = 45))
    assertFailure { CommandLine.rampOptions(listOf("--every", "5")) }.hasMessage(
      "STEP must be a whole number of seconds between 30 and 300, for example `make ramp STEP=45`."
    )
    assertFailure { CommandLine.rampOptions(listOf("--every")) }.isInstanceOf(UsageError::class)
    assertFailure { CommandLine.rampOptions(listOf("--steps", "3")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `bench takes a client count and a length within bounds`() {
    assertThat(CommandLine.benchOptions(emptyList())).isEqualTo(BenchOptions(clients = 20, seconds = 120))
    assertThat(CommandLine.benchOptions(listOf("--clients", "50", "--seconds", "180"))).isEqualTo(BenchOptions(clients = 50, seconds = 180))
    assertFailure { CommandLine.benchOptions(listOf("--clients", "0")) }.hasMessage("CLIENTS must be a whole number between 1 and 200, for example `make bench CLIENTS=50`.")
    assertFailure { CommandLine.benchOptions(listOf("--seconds", "10")) }.isInstanceOf(UsageError::class)
    assertFailure { CommandLine.benchOptions(listOf("--rate", "10")) }.isInstanceOf(UsageError::class)
  }

  @Test
  fun `make new starts only the incident and toolbox modes`() {
    assertFailure { CommandLine.newOptions(listOf("--mode", Exercises.RAMP)) }.hasMessage("MODE must be one of incident, toolbox.")
    assertFailure { CommandLine.newOptions(listOf("--mode", Exercises.BENCH)) }.isInstanceOf(UsageError::class)
  }
}

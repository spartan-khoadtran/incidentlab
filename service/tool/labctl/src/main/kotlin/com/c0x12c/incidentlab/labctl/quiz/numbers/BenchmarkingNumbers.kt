package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import java.util.Locale
import kotlin.random.Random

/**
 * Numeric drills for docs/benchmarking.md. Each generator draws from a short list of values and hands them
 * to a formula function, so a test can check the formula on fixed values.
 */
internal object BenchmarkingNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    { r -> closedLoopThroughput(CLIENTS.random(r), RESPONSE_MS.random(r), THINK_MS.random(r)) },
    { r -> closedLoopClients(TARGET_RPS.random(r), SHORT_RESPONSE_MS.random(r), LONG_THINK_MS.random(r)) },
    { r -> closedLoopSlowdown(SHORT_RESPONSE_MS.random(r), SLOWDOWN.random(r), THINK_MS.random(r)) },
    { r -> coordinatedOmission(STALL_SECONDS.random(r), INTENDED_RPS.random(r)) },
    { r -> omittedSlowShare(STALL_SECONDS.random(r), RUN_SECONDS.random(r), INTENDED_RPS.random(r)) },
    { r -> successThroughput(ACHIEVED_RPS.random(r), FAILED_PERCENT.random(r)) }
  )

  fun closedLoopThroughput(clients: Int, responseMs: Int, thinkMs: Int): NumericQuestion {
    val cycleSeconds = (responseMs + thinkMs) / MILLIS_PER_SECOND
    val answer = clients / cycleSeconds
    return NumericQuestion(
      "A closed-loop load tool runs $clients clients. Each one sends its next request after the reply, after a think time of $thinkMs ms. The response time is $responseMs ms. What rate does the tool offer, in requests/s?",
      answer, Tolerance.Relative(0.02), "requests/s",
      "X = N / (R + Z) = $clients / (${shown(responseMs / MILLIS_PER_SECOND)} + ${shown(thinkMs / MILLIS_PER_SECOND)}) s = $clients / ${shown(cycleSeconds)} = ${shown(answer)} requests/s. When R grows, X falls by itself, so the server never looks overloaded.",
      "Open and closed load models > Closed loop"
    )
  }

  fun closedLoopClients(targetRps: Int, responseMs: Int, thinkMs: Int): NumericQuestion {
    val cycleSeconds = (responseMs + thinkMs) / MILLIS_PER_SECOND
    val answer = targetRps * cycleSeconds
    return NumericQuestion(
      "You want a closed-loop tool to offer $targetRps requests/s. The response time is $responseMs ms and the think time is $thinkMs ms. How many clients does the tool need?",
      answer, Tolerance.Relative(0.01), "clients",
      "N = X × (R + Z) = $targetRps × (${shown(responseMs / MILLIS_PER_SECOND)} + ${shown(thinkMs / MILLIS_PER_SECOND)}) s = $targetRps × ${shown(cycleSeconds)} = ${shown(answer)} clients. Fewer clients cannot reach the rate, and when the server slows, even these fall short of it.",
      "Open and closed load models > Closed loop"
    )
  }

  fun closedLoopSlowdown(responseMs: Int, factor: Int, thinkMs: Int): NumericQuestion {
    val slowMs = responseMs * factor
    val before = responseMs + thinkMs
    val after = slowMs + thinkMs
    val answer = (1 - before.toDouble() / after) * PERCENT
    return NumericQuestion(
      "A closed-loop tool has a think time of $thinkMs ms. The server's response time goes from $responseMs ms to $slowMs ms. By what percentage does the offered rate fall?",
      answer, Tolerance.Absolute(0.5), "%",
      "X = N / (R + Z), so the rate scales with 1 / (R + Z): the cycle goes from $before ms to $after ms. The rate falls by 1 − $before / $after = ${String.format(Locale.ROOT, "%.1f", answer)}%. The closed tool offers less load as the server slows, so no queue ever builds.",
      "Open and closed load models > Closed loop"
    )
  }

  fun coordinatedOmission(stallSeconds: Int, intendedRps: Int): NumericQuestion {
    val answer = (stallSeconds * intendedRps).toDouble()
    return NumericQuestion(
      "A closed-loop tool runs at an intended $intendedRps requests/s. The server stalls once for $stallSeconds s. How many requests would the tool have sent during the stall at its intended rate?",
      answer, Tolerance.Relative(0.01), "requests",
      "$stallSeconds s × $intendedRps requests/s = ${shown(answer)}. The tool sent 1 of them, the one in flight, and measured it at $stallSeconds s. The other ${shown(answer - 1)} never reach the histogram, so the measured p99 understates the user's p99. Time each request from its intended send time, or use an open model.",
      "Open and closed load models > Coordinated omission"
    )
  }

  fun omittedSlowShare(stallSeconds: Int, runSeconds: Int, intendedRps: Int): NumericQuestion {
    val slow = (stallSeconds - 1) * intendedRps
    val intended = runSeconds * intendedRps
    val measuredSamples = intended - (stallSeconds * intendedRps - 1)
    val answer = slow.toDouble() / intended * PERCENT
    val measuredShare = 1.0 / measuredSamples * PERCENT
    return NumericQuestion(
      "A closed-loop tool at an intended $intendedRps requests/s runs for $runSeconds s. The server stalls once for $stallSeconds s. What share of the intended requests would have waited more than 1 s, in %?",
      answer, Tolerance.Relative(0.02), "%",
      "Requests due in the first ${stallSeconds - 1} s of the stall wait more than 1 s: ${stallSeconds - 1} × $intendedRps = $slow of $runSeconds × $intendedRps = $intended intended requests, so $slow / $intended = ${String.format(Locale.ROOT, "%.2f", answer)}%. The closed tool measured 1 slow request out of $measuredSamples, about ${String.format(Locale.ROOT, "%.2f", measuredShare)}%. That gap is coordinated omission.",
      "Open and closed load models > Coordinated omission"
    )
  }

  fun successThroughput(achievedRps: Int, failedPercent: Int): NumericQuestion {
    val successShare = 1 - failedPercent / PERCENT
    val answer = achievedRps * successShare
    return NumericQuestion(
      "A load test achieved $achievedRps requests/s, and $failedPercent% of them failed. What is the success throughput, in requests/s?",
      answer, Tolerance.Relative(0.01), "requests/s",
      "$achievedRps × (1 − ${shown(failedPercent / PERCENT)}) = $achievedRps × ${shown(successShare)} = ${shown(answer)} requests/s. Errors are fast, so the run with errors also shows a better p99 than a healthy one. Report the success share next to the latency, always.",
      "Benchmarking mistakes > Ignoring errors"
    )
  }

  private val CLIENTS = listOf(10, 20, 50, 100)
  private val RESPONSE_MS = listOf(50, 100, 200, 400)
  private val THINK_MS = listOf(0, 150, 400, 900)
  private val TARGET_RPS = listOf(100, 200, 500)
  private val SHORT_RESPONSE_MS = listOf(50, 100)
  private val LONG_THINK_MS = listOf(150, 400, 900)
  private val SLOWDOWN = listOf(3, 5, 9)
  private val STALL_SECONDS = listOf(2, 3, 5, 10)
  private val INTENDED_RPS = listOf(50, 100, 200, 500)
  private val RUN_SECONDS = listOf(60, 120, 300)
  private val ACHIEVED_RPS = listOf(320, 800, 1_200, 2_500)
  private val FAILED_PERCENT = listOf(5, 8, 20, 30)

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
}

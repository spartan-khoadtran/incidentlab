package com.c0x12c.incidentlab.labctl.quiz.numbers

import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import com.c0x12c.incidentlab.labctl.quiz.Quiz.shown
import com.c0x12c.incidentlab.labctl.quiz.Tolerance
import kotlin.random.Random

/** Numeric drills for docs/cpu.md: shares, credits, lock waits and fork storms. The formulas are plain functions so a test can check them by hand. */
internal object CpuNumbers {

  val generators: List<(Random) -> NumericQuestion> = listOf(
    ::weightSplit, ::creditsEarned, ::creditsRunOut, ::lockUtilization, ::lockWait, ::forkCores
  )

  /** The share of the CPU container A gets when A and B both want all of it. */
  internal fun weightSharePercent(weightA: Int, weightB: Int): Double = weightA.toDouble() / (weightA + weightB) * PERCENT

  /** Credits a burstable VM earns in an hour: baseline × vCPUs × 60 minutes. */
  internal fun creditsPerHour(vcpus: Int, baselinePercent: Int): Double = baselinePercent / PERCENT * vcpus * MINUTES_PER_HOUR

  /** Minutes until the balance is gone at a steady utilization above the baseline. */
  internal fun minutesUntilCreditsGone(balance: Int, vcpus: Int, utilizationPercent: Int, baselinePercent: Int): Double =
    balance / (vcpus * (utilizationPercent - baselinePercent) / PERCENT)

  /** Utilization law for a lock: requests per second × hold time. */
  internal fun lockUtilizationPercent(rps: Int, holdMs: Int): Double = rps * holdMs / MILLIS_PER_SECOND * PERCENT

  /** M/M/1: the mean wait in the queue is ρ / (1 − ρ) times the service time. */
  internal fun lockWaitMs(utilizationPercent: Int, holdMs: Int): Double {
    val rho = utilizationPercent / PERCENT
    return rho / (1 - rho) * holdMs
  }

  /** Cores burned by a fork storm: forks per second × CPU per fork. */
  internal fun forkCores(forksPerSecond: Int, cpuMsEach: Double): Double = forksPerSecond * cpuMsEach / MILLIS_PER_SECOND

  private fun weightSplit(r: Random): NumericQuestion {
    val a = listOf(100, 300, 500, 2_500, 3_000).random(r)
    val b = listOf(100, 200).random(r)
    val answer = weightSharePercent(a, b)
    return NumericQuestion(
      "Two containers on one host both want every CPU cycle. Container A has cpu.weight $a, container B has cpu.weight $b. What share of the CPU does A get, in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "A gets $a / ($a + $b) = ${shown(answer)}% and B gets the rest. The split only exists under contention: when B sleeps, A gets whatever it asks for and the weight does nothing.",
      "CPU shares and quotas > Shares only matter under contention"
    )
  }

  private fun creditsEarned(r: Random): NumericQuestion {
    val vcpus = listOf(1, 2, 4).random(r)
    val baseline = listOf(5, 10, 20, 30, 40).random(r)
    val answer = creditsPerHour(vcpus, baseline)
    return NumericQuestion(
      "A burstable VM has $vcpus vCPU(s) and a baseline of $baseline% per vCPU. One credit is one vCPU at 100% for one minute. How many credits does it earn per hour?",
      answer, Tolerance.Relative(0.01), "credits",
      "${shown(baseline / PERCENT)} × $vcpus vCPU × 60 minutes = ${shown(answer)} credits per hour. Below the baseline the VM banks them; above it, it spends them faster than it earns.",
      "CPU shares and quotas > CPU credits on burstable instances"
    )
  }

  private fun creditsRunOut(r: Random): NumericQuestion {
    val vcpus = listOf(1, 2).random(r)
    val baseline = listOf(10, 20, 30).random(r)
    val utilization = listOf(60, 80, 100).random(r)
    val balance = listOf(60, 144, 288, 576).random(r)
    val answer = minutesUntilCreditsGone(balance, vcpus, utilization, baseline)
    val net = vcpus * (utilization - baseline) / PERCENT
    return NumericQuestion(
      "A burstable VM has $vcpus vCPU(s), a baseline of $baseline% and $balance credits in the bank. A job pins it at $utilization% on every vCPU. In how many minutes is the balance gone?",
      answer, Tolerance.Relative(0.02), "minutes",
      "Net spend per minute = $vcpus × (${shown(utilization / PERCENT)} − ${shown(baseline / PERCENT)}) = ${shown(net)} credits. $balance / ${shown(net)} = ${shown(answer)} minutes. Then the VM drops to $baseline% and stays there until it earns credits again.",
      "CPU shares and quotas > CPU credits on burstable instances"
    )
  }

  private fun lockUtilization(r: Random): NumericQuestion {
    val rps = listOf(10, 15, 20).random(r)
    val hold = listOf(20, 30, 45).random(r)
    val answer = lockUtilizationPercent(rps, hold)
    return NumericQuestion(
      "Every request takes one global lock and holds it for $hold ms. Requests arrive at $rps/s. What is the lock's utilization in %?",
      answer, Tolerance.Absolute(0.5), "%",
      "Utilization law: $rps × ${shown(hold / MILLIS_PER_SECOND)} s = ${shown(answer)}%. The lock is a software resource: its utilization is the share of time it is held, which rate(held_seconds_total) gives you directly.",
      "Off-CPU time > Waiting on a lock"
    )
  }

  private fun lockWait(r: Random): NumericQuestion {
    val utilization = listOf(50, 75, 80, 90, 95).random(r)
    val hold = listOf(20, 50, 60, 100).random(r)
    val answer = lockWaitMs(utilization, hold)
    val rho = utilization / PERCENT
    return NumericQuestion(
      "A lock is held $hold ms per request and runs at $utilization% utilization. By M/M/1, what is the mean time a request waits for the lock, in ms?",
      answer, Tolerance.Relative(0.02), "ms",
      "W = S × ρ / (1 − ρ) = $hold × ${shown(rho)} / ${shown(1 - rho)} = ${shown(answer)} ms, that is ${shown(rho / (1 - rho))} hold times. CPU stays low the whole time: a waiting thread burns nothing.",
      "Off-CPU time > Waiting on a lock"
    )
  }

  private fun forkCores(r: Random): NumericQuestion {
    val rate = listOf(100, 200, 500, 1_000).random(r)
    val cost = listOf(0.5, 1.0, 1.5, 2.0).random(r)
    val answer = forkCores(rate, cost)
    return NumericQuestion(
      "A host starts $rate short-lived processes per second. Each fork, exec and exit costs about ${shown(cost)} ms of CPU. How many cores does the storm use?",
      answer, Tolerance.Relative(0.02), "cores",
      "$rate × ${shown(cost / MILLIS_PER_SECOND)} s = ${shown(answer)} cores, with no process in top to blame. node_forks_total sees the rate; the host's load and PSI say whether anyone waits behind it.",
      "CPU busy with no work > Short-lived processes"
    )
  }

  private const val PERCENT = 100.0
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val MINUTES_PER_HOUR = 60
}

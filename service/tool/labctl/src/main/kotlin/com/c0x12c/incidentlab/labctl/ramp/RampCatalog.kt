package com.c0x12c.incidentlab.labctl.ramp

import com.c0x12c.incidentlab.labctl.scenario.Baseline.API_CORES_AT_BASE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_HOLD_MS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_CHECKOUT_SHARE
import com.c0x12c.incidentlab.labctl.scenario.Baseline.BASE_RPS
import com.c0x12c.incidentlab.labctl.scenario.Baseline.MILLIS_PER_SECOND
import com.c0x12c.incidentlab.labctl.scenario.Baseline.PERCENT
import com.c0x12c.incidentlab.labctl.scenario.FaultSource
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The hidden setups of `make ramp`. Like the incident catalog, this file holds the answer. One setup
 * limits the lab, the ramp raises loadgen's surge multiplier step by step, and after the last step
 * every override is gone so the lab recovers.
 *
 * Each limit is drawn so it fills inside the ramp's range (50 to 300 rps). A pool of 6 would fill at
 * 400 rps and api at 0.5 cores at about 420 rps, both past the last step, so neither is drawn.
 * The setups that do not limit the pool open it to the real Hikari size, because the default pool
 * of 4 fills at about 267 rps and would be a second, hidden answer.
 */
object RampCatalog : FaultSource {

  const val ID = "ramp"
  const val POOL = "pool"
  const val CPU = "cpu"
  const val NONE = "none"

  /** The options of the third question. */
  val RESOURCES: List<Pair<String, String>> = listOf(
    POOL to "The api's DB connection pool: in use reached max and waiting rose above 0",
    CPU to "The api container's CPU limit: usage reached the limit and throttled periods rose",
    "pricing" to "The pricing service: its own p99 rose first",
    "worker" to "The Kafka consumer: lag grew",
    NONE to "Nothing within the range: p99 stayed near the first step's and errors stayed near 0"
  )

  val POOL_LIMITS: List<Int> = listOf(2, 3, 4)
  val CPU_LIMITS: List<Double> = listOf(0.2, 0.3)
  const val OPEN_POOL = 24
  const val BASE_CPUS = 2.0

  fun params(random: Random, every: Int): Map<String, Any> =
    when (val setup = listOf(POOL, CPU, NONE).random(random)) {
      POOL -> mapOf("setup" to setup, "pool_max" to POOL_LIMITS.random(random), "cpus" to BASE_CPUS, "every" to every)
      CPU -> mapOf("setup" to setup, "pool_max" to OPEN_POOL, "cpus" to CPU_LIMITS.random(random), "every" to every)
      else -> mapOf("setup" to setup, "pool_max" to OPEN_POOL, "cpus" to BASE_CPUS, "every" to every)
    }

  override fun faults(id: String, params: Params): FaultSpec? {
    if (id != ID) return null
    val step = RampPlan.stepAt(params.elapsedSeconds, params.int("every")) ?: return FaultSpec()
    return FaultSpec(
      services = mapOf(
        "loadgen" to mapOf("surge_multiplier" to RampPlan.MULTIPLIERS[step]),
        "api" to mapOf("pool_max" to params.int("pool_max"))
      ),
      docker = mapOf("api" to mapOf("cpus" to params.double("cpus")))
    )
  }

  fun resource(params: Params): String = params.string("setup")

  fun title(params: Params): String =
    when (resource(params)) {
      POOL -> "Capacity ramp (pool_max = ${params.int("pool_max")})"
      CPU -> "Capacity ramp (api cpus = ${params.double("cpus")})"
      else -> "Capacity ramp (no extra limit)"
    }

  /** The total rate at which a pool of this size is full, from Little's Law on the checkouts alone. */
  fun poolFullRps(pool: Int): Double = pool * MILLIS_PER_SECOND / BASE_CHECKOUT_HOLD_MS / BASE_CHECKOUT_SHARE

  /** The total rate at which api uses up this many cores, if its CPU cost per request stays what it is at the base load. */
  fun cpuFullRps(cpus: Double): Double = cpus / API_CORES_AT_BASE * BASE_RPS

  /** The hidden limit, where the numbers say it fills, and the panel that showed it. */
  fun explain(params: Params): String =
    when (resource(params)) {
      POOL -> {
        val pool = params.int("pool_max")
        val checkouts = pool * MILLIS_PER_SECOND / BASE_CHECKOUT_HOLD_MS
        val total = poolFullRps(pool).roundToInt()
        val before = RampPlan.rates.lastOrNull { it < total } ?: RampPlan.rates.first()
        val busy = (BASE_CHECKOUT_SHARE * before * BASE_CHECKOUT_HOLD_MS / MILLIS_PER_SECOND / pool * PERCENT).roundToInt()
        "The hidden limit was pool_max = $pool (the default is 4). A checkout holds a connection about $BASE_CHECKOUT_HOLD_MS ms. " +
          "By Little's Law the pool is full at $pool / ${params.fixed(BASE_CHECKOUT_HOLD_MS / MILLIS_PER_SECOND, 2)} s = ${checkouts.roundToInt()} checkouts/s. " +
          "Checkouts are ${(BASE_CHECKOUT_SHARE * PERCENT).roundToInt()}% of the traffic, so that is about $total rps in total. " +
          "At $before rps the pool is already $busy% busy, and the queue shows in p99 before the pool is full. " +
          "The panels that showed it: dashboard 02, row 1, DB pool in use against max, and waiting above 0."
      }
      CPU -> {
        val cpus = params.double("cpus")
        val total = cpuFullRps(cpus).roundToInt()
        "The hidden limit was api cpus = $cpus (the default is $BASE_CPUS). The pool was opened to $OPEN_POOL, so it stayed out of the way. " +
          "api uses about $API_CORES_AT_BASE cores at $BASE_RPS rps, so the limit is used up at about $cpus / $API_CORES_AT_BASE x $BASE_RPS = $total rps. " +
          "Throttled periods climb before that, because the quota runs out inside single 100 ms periods when requests come in a burst. " +
          "The panels that showed it: dashboard 02, row 3, CPU usage against the limit and the share of throttled periods."
      }
      else -> {
        val poolAt = poolFullRps(OPEN_POOL).roundToInt()
        val cores = params.fixed(API_CORES_AT_BASE * RampPlan.MULTIPLIERS.last(), 2)
        "There was no extra limit. The pool was opened to $OPEN_POOL and api kept its $BASE_CPUS cores. A pool of $OPEN_POOL fills at about $poolAt rps, " +
          "and api needs about $cores cores at ${RampPlan.rates.last()} rps. Both are far above the range, so the right answer was nothing within the range. " +
          "The panels that showed it: pool in use well under max, CPU usage well under the limit, and a p99 that stayed close to the first step's."
      }
    }
}

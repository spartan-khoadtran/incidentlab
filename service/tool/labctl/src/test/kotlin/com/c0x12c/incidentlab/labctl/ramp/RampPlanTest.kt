package com.c0x12c.incidentlab.labctl.ramp

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import com.c0x12c.incidentlab.labctl.scenario.FaultSpec
import com.c0x12c.incidentlab.labctl.scenario.Params
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.jupiter.api.Test

class RampPlanTest {

  @Test
  fun `the steps run from 50 to 300 rps`() {
    assertThat(RampPlan.rates).containsExactly(50, 75, 100, 125, 150, 200, 250, 300)
    assertThat(RampPlan.totalSeconds(60)).isEqualTo(480)
    assertThat(RampPlan.windowSeconds(60)).isEqualTo(50)
  }

  @Test
  fun `the step follows the elapsed time and ends after the last one`() {
    assertThat(RampPlan.stepAt(0.0, 60)).isEqualTo(0)
    assertThat(RampPlan.stepAt(59.9, 60)).isEqualTo(0)
    assertThat(RampPlan.stepAt(60.0, 60)).isEqualTo(1)
    assertThat(RampPlan.stepAt(479.0, 60)).isEqualTo(7)
    assertThat(RampPlan.stepAt(480.0, 60)).isNull()
    assertThat(RampPlan.stepAt(-1.0, 60)).isNull()
    assertThat(RampPlan.stepsRun(59.0, 60)).isEqualTo(0)
    assertThat(RampPlan.stepsRun(130.0, 60)).isEqualTo(2)
    assertThat(RampPlan.stepsRun(10_000.0, 60)).isEqualTo(8)
  }

  @Test
  fun `the catalog drives the surge and the hidden limit by step, then lets go`() {
    val values = mapOf<String, Any?>("setup" to RampCatalog.POOL, "pool_max" to 3, "cpus" to 2.0, "every" to 45)
    fun at(seconds: Double): FaultSpec? = RampCatalog.faults(RampCatalog.ID, Params(values, "", seconds))

    assertThat(at(10.0)!!.services.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("surge_multiplier" to 1.0, "rate_multiplier" to 1.0))
    assertThat(at(100.0)!!.services.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("surge_multiplier" to 2.0, "rate_multiplier" to 1.0))
    assertThat(at(100.0)!!.services.getValue("api")).isEqualTo(mapOf<String, Any?>("pool_max" to 3))
    assertThat(at(359.0)!!.services.getValue("loadgen")).isEqualTo(mapOf<String, Any?>("surge_multiplier" to 6.0, "rate_multiplier" to 1.0))
    assertThat(at(100.0)!!.docker).isEqualTo(mapOf("api" to mapOf("cpus" to 2.0)))
    assertThat(at(360.0)).isEqualTo(FaultSpec())
    assertThat(RampCatalog.faults("pool_hold", Params(values, "", 10.0))).isNull()
  }

  @Test
  fun `every drawn limit fills inside the range, and the open setups never fill in it`() {
    val top = RampPlan.rates.last().toDouble()
    val bottom = RampPlan.rates[1].toDouble()
    RampCatalog.POOL_LIMITS.forEach {
      assertThat(RampCatalog.poolFullRps(it)).isGreaterThan(bottom)
      assertThat(RampCatalog.poolFullRps(it)).isLessThan(top)
    }
    RampCatalog.CPU_LIMITS.forEach {
      assertThat(RampCatalog.cpuFullRps(it)).isGreaterThan(bottom)
      assertThat(RampCatalog.cpuFullRps(it)).isLessThan(top)
    }
    assertThat(RampCatalog.poolFullRps(RampCatalog.OPEN_POOL)).isGreaterThan(top * 2)
    assertThat(RampCatalog.cpuFullRps(RampCatalog.BASE_CPUS)).isGreaterThan(top * 2)
  }

  @Test
  fun `every setup renders a title and an explanation with numbers`() {
    val random = Random(3)
    val seen = mutableSetOf<String>()
    repeat(60) {
      val params = Params(RampCatalog.params(random, 60), "")
      seen += RampCatalog.resource(params)
      val text = RampCatalog.title(params) + RampCatalog.explain(params)
      assertThat(text.contains("null") || text.contains("{")).isEqualTo(false)
      val limit = RampCatalog.faults(RampCatalog.ID, Params(params.values, "", 0.0))!!
      assertThat(limit.services.getValue("api").getValue("pool_max") as Int in RampCatalog.POOL_LIMITS + RampCatalog.OPEN_POOL).isEqualTo(true)
    }
    assertThat(seen).isEqualTo(setOf(RampCatalog.POOL, RampCatalog.CPU, RampCatalog.NONE))
  }

  @Test
  fun `a pool of 3 is full at 200 rps`() {
    assertThat(RampCatalog.poolFullRps(3).roundToInt()).isEqualTo(200)
    val text = RampCatalog.explain(Params(mapOf("setup" to RampCatalog.POOL, "pool_max" to 3, "cpus" to 2.0, "every" to 60), ""))
    assertThat(text.contains("3 / 0.05 s = 60 checkouts/s")).isEqualTo(true)
    assertThat(text.contains("about 200 rps in total")).isEqualTo(true)
    assertThat(text.contains("At 150 rps the pool is already 75% busy")).isEqualTo(true)
  }
}

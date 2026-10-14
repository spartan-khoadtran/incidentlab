package com.c0x12c.redlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.c0x12c.redlab.labctl.control.Services
import kotlin.random.Random
import org.junit.jupiter.api.Test

class ScenarioCatalogTest {

  private val natures = Answers.NATURES.map { it.first }.toSet()
  private val locations = Answers.LOCATIONS.map { it.first }.toSet()
  private val signals = Answers.SIGNALS.map { it.first }.toSet()
  private val causes = Answers.CAUSES.map { it.first }.toSet()

  @Test
  fun `twenty-four scenarios with unique ids and valid answer keys`() {
    assertThat(ScenarioCatalog.all).hasSize(24)
    assertThat(ScenarioCatalog.all.map { it.id }.toSet()).hasSize(24)
    for (scenario in ScenarioCatalog.all) {
      assertThat(scenario.level).isBetween(1, 3)
      assertThat(scenario.answer.nature.all { it in natures }).isTrue()
      assertThat(scenario.answer.location.all { it in locations }).isTrue()
      assertThat(scenario.answer.signal.isNotEmpty() && scenario.answer.signal.all { it in signals }).isTrue()
      assertThat(scenario.answer.cause in causes).isTrue()
      assertThat(scenario.answer.cause in Answers.DISTRACTORS).isFalse()
      assertThat(scenario.answer.partial.all { it in causes && it !in Answers.DISTRACTORS }).isTrue()
      assertThat(scenario.topics.all { it in Answers.TOPICS }).isTrue()
      assertThat(scenario.hints).hasSize(3)
      assertThat(scenario.evidence).isNotEmpty()
    }
  }

  @Test
  fun `every real cause and every signal is the answer of at least one scenario`() {
    val usedCauses = ScenarioCatalog.all.map { it.answer.cause }.toSet()
    val usedSignals = ScenarioCatalog.all.flatMap { it.answer.signal }.toSet()
    assertThat(causes - Answers.DISTRACTORS - usedCauses).isEmpty()
    assertThat(signals - usedSignals).isEmpty()
    assertThat(Answers.CAUSES.map { it.first }.all { it in Answers.CAUSE_GROUPS }).isTrue()
  }

  @Test
  fun `a cause is no longer a one-to-one key for a scenario`() {
    val scenariosPerCause = ScenarioCatalog.all.groupingBy { it.answer.cause }.eachCount()
    assertThat(scenariosPerCause.getValue("pool_exhausted")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("single_thread_cpu")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("client_timeout")).isEqualTo(2)
  }

  @Test
  fun `look-alike pairs share a symptom and differ in cause or signal`() {
    val pairs = listOf("consumer_backlog" to "consumer_slow", "downstream_slow" to "pricing_cpu", "retry_storm" to "downstream_errors")
    for ((left, right) in pairs) {
      val a = ScenarioCatalog.byId.getValue(left).answer
      val b = ScenarioCatalog.byId.getValue(right).answer
      assertThat(a.cause != b.cause || a.signal != b.signal).isTrue()
    }
    assertThat(ScenarioCatalog.byId.getValue("consumer_slow").answer.partial).containsOnly("consumer_backlog")
    assertThat(ScenarioCatalog.byId.getValue("pricing_cpu").answer.partial).containsOnly("downstream_slow")

    val hold = ScenarioCatalog.byId.getValue("pool_hold").answer
    val shrunk = ScenarioCatalog.byId.getValue("pool_shrunk").answer
    assertThat(hold.cause).isEqualTo(shrunk.cause)
    assertThat(hold.signal).isEqualTo(shrunk.signal)
  }

  @Test
  fun `every scenario renders its texts and targets known services`() {
    val random = Random(42)
    for (scenario in ScenarioCatalog.all) {
      repeat(5) {
        val params = Params(scenario.params(random, ExerciseContext(cpuCount = 8, productDetailSeconds = 0.015)), "14:05")
        val spec = scenario.faults(params)
        assertThat(spec.services.keys.all { it in Services.ENDPOINTS }).isTrue()
        assertThat(spec.docker.keys.all { it in Services.BASE_DOCKER }).isTrue()
        val ticket = scenario.ticket(params)
        val explain = scenario.explain(params)
        assertThat(ticket.contains("14:05")).isTrue()
        assertThat(explain.contains("null")).isFalse()
        assertThat(explain.contains("{")).isFalse()
        scenario.marker?.let { assertThat(it(params).contains("null")).isFalse() }
      }
    }
  }

  @Test
  fun `the crawler rate is calibrated to the measured query cost and clamped`() {
    val scenario = ScenarioCatalog.byId.getValue("tenant_spike")
    val cheap = scenario.params(Random(1), ExerciseContext(productDetailSeconds = 0.001))["extra"] as Int
    val expensive = scenario.params(Random(1), ExerciseContext(productDetailSeconds = 0.5))["extra"] as Int
    assertThat(cheap).isEqualTo(600)
    assertThat(expensive).isEqualTo(40)
  }

  @Test
  fun `the noisy neighbour sizes itself to the host`() {
    val scenario = ScenarioCatalog.byId.getValue("noisy_neighbor")
    val spec = scenario.faults(Params(scenario.params(Random(1), ExerciseContext(cpuCount = 6)), ""))
    assertThat(spec.services.keys).containsOnly("noisy")
    assertThat(spec.services.getValue("noisy")["burners"]).isEqualTo(12)
  }

  @Test
  fun `pool hold and downstream slow draw both of their variants`() {
    val random = Random(5)
    val overloads = (1..30).map { ScenarioCatalog.byId.getValue("pool_hold").params(random, ExerciseContext())["overload"] as Boolean }.toSet()
    val tails = (1..30).map { ScenarioCatalog.byId.getValue("downstream_slow").params(random, ExerciseContext())["tail"] as Boolean }.toSet()
    assertThat(overloads).hasSize(2)
    assertThat(tails).hasSize(2)

    val shift = ScenarioCatalog.byId.getValue("downstream_slow")
    val params = Params(mapOf("tail" to false, "extra" to 300, "frac" to 1.0), "14:05")
    assertThat(shift.faults(params).services.getValue("pricing")).isEqualTo(mapOf("price_base_ms" to 312))
  }

  @Test
  fun `the client outage removes one tenant and keeps the others`() {
    val scenario = ScenarioCatalog.byId.getValue("client_outage")
    val params = Params(mapOf("tenant" to "ios"), "14:05")
    val tenants = scenario.faults(params).services.getValue("loadgen").getValue("tenants") as Map<*, *>
    assertThat(tenants["ios"]).isEqualTo(0)
    assertThat(tenants["web"]).isEqualTo(ScenarioCatalog.BASE_TENANTS.getValue("web"))
    assertThat(scenario.ticket(params).contains("30%")).isTrue()
    assertThat(scenario.marker!!(params).contains("iOS")).isTrue()
  }
}

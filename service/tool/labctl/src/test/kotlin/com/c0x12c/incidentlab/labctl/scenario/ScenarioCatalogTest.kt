package com.c0x12c.incidentlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.c0x12c.incidentlab.labctl.control.Services
import com.c0x12c.incidentlab.labctl.scenario.catalog.AppScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.AsyncScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.ContainerHostScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.DatabaseScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.DownstreamScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.EdgeScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.LoadScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.ProfilingScenarios
import kotlin.random.Random
import org.junit.jupiter.api.Test

class ScenarioCatalogTest {

  private val natures = Answers.NATURES.map { it.first }.toSet()
  private val locations = Answers.LOCATIONS.map { it.first }.toSet()
  private val signals = Answers.SIGNALS.map { it.first }.toSet()
  private val causes = Answers.CAUSES.map { it.first }.toSet()
  private val layers = listOf(
    LoadScenarios.all, EdgeScenarios.all, AppScenarios.all, DatabaseScenarios.all, DownstreamScenarios.all, AsyncScenarios.all, ContainerHostScenarios.all,
    ProfilingScenarios.all
  )

  @Test
  fun `every layer adds scenarios with unique ids and valid answer keys`() {
    assertThat(layers.all { it.isNotEmpty() }).isTrue()
    assertThat(ScenarioCatalog.all).hasSize(layers.sumOf { it.size })
    assertThat(ScenarioCatalog.all.map { it.id }.toSet()).hasSize(ScenarioCatalog.all.size)
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
      assertThat(scenario.steps.size).isBetween(4, 8)
      assertThat(scenario.evidence).isNotEmpty()
    }
  }

  @Test
  fun `the CLI proofs cover the core scenarios and say what to read`() {
    val withCli = ScenarioCatalog.all.filter { it.cli.isNotEmpty() }
    assertThat(withCli.size >= MIN_TOOLBOX_SCENARIOS).isTrue()
    assertThat(TOOLBOX_CORE.filter { ScenarioCatalog.byId.getValue(it).cli.isEmpty() }).isEmpty()
    for (command in withCli.flatMap { it.cli }) {
      assertThat(command.isNotBlank()).isTrue()
      assertThat(command.substringBefore(COMMENT).isNotBlank() && command.substringAfter(COMMENT, "").isNotBlank()).isTrue()
      assertThat(command.contains("\${")).isFalse()
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
    assertThat(scenariosPerCause.getValue("downstream_slow")).isEqualTo(3)
    assertThat(scenariosPerCause.getValue("lb_no_healthy")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("global_load")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("single_thread_cpu")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("client_timeout")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("cache_miss")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("downstream_errors")).isEqualTo(2)
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
  fun `the client outage mutes one tenant through its own loadgen key`() {
    val scenario = ScenarioCatalog.byId.getValue("client_outage")
    val params = Params(scenario.params(Random(2), ExerciseContext(tenants = mapOf("web" to 40, "ios" to 10, "android" to 10))), "14:05")
    assertThat(scenario.faults(params).services.getValue("loadgen")).isEqualTo(mapOf("tenant_outage" to params.string("tenant")))
    assertThat(scenario.ticket(params).contains("17%")).isTrue()
    assertThat(scenario.marker!!(params).contains(if (params.string("tenant") == "ios") "iOS" else "Android")).isTrue()

    val fallback = Params(scenario.params(Random(2), ExerciseContext()), "14:05")
    assertThat(fallback.int("total")).isEqualTo(Baseline.BASE_RPS)
  }

  @Test
  fun `the global surge leaves the learner's load multiplier alone`() {
    val scenario = ScenarioCatalog.byId.getValue("global_surge")
    val loadgen = scenario.faults(Params(scenario.params(Random(3), ExerciseContext()), "14:05")).services.getValue("loadgen")
    assertThat(loadgen.keys).containsOnly("surge_multiplier")
  }

  @Test
  fun `the hot code path puts 1_2 to 1_6 cores of render work on api alone`() {
    val scenario = ScenarioCatalog.byId.getValue("hot_code_path")
    val random = Random(9)
    repeat(30) {
      val params = Params(scenario.params(random, ExerciseContext()), "14:05")
      val spec = scenario.faults(params)
      assertThat(spec.services.keys).containsOnly("api")
      assertThat(spec.services.getValue("api").keys).containsOnly("render_ms")
      val cores = Baseline.BASE_RPS * params.int("render") / Baseline.MILLIS_PER_SECOND
      assertThat(cores).isBetween(1.2, 1.6)
    }
    val fixed = Params(mapOf("render" to 27), "14:05")
    assertThat(scenario.explain(fixed).contains("50 × 0.027 s = 1.35 cores")).isTrue()
    assertThat(scenario.explain(fixed).contains("about 68% busy")).isTrue()
    assertThat(scenario.answer.partial).containsOnly("single_thread_cpu")
    assertThat(scenario.cli).isNotEmpty()
  }

  @Test
  fun `the baseline numbers come from one tenant mix`() {
    assertThat(Baseline.BASE_RPS).isEqualTo(50)
    assertThat(Baseline.BASE_TENANTS.values.sum()).isEqualTo(Baseline.BASE_RPS)
  }

  @Test
  fun `a flag missing from an older exercise reads as false`() {
    assertThat(Params(mapOf("recommend_ms" to 300), "14:05").bool("overload")).isFalse()
    val scenario = ScenarioCatalog.byId.getValue("pool_hold")
    assertThat(scenario.ticket(Params(mapOf("recommend_ms" to 300), "14:05")).contains("errors are rare")).isTrue()
  }

  @Test
  fun `cpu credits run out only after the drawn credit time`() {
    val scenario = ScenarioCatalog.byId.getValue("cpu_credits")
    val random = Random(4)
    repeat(20) {
      val values = scenario.params(random, ExerciseContext())
      val credit = (values["credit_seconds"] as Int)
      assertThat(credit).isBetween(120, 240)
      assertThat(values["cpus"] as Double).isBetween(0.07, 0.1)
      val before = scenario.faults(Params(values, "14:05", elapsedSeconds = credit - 1.0))
      val after = scenario.faults(Params(values, "14:05", elapsedSeconds = credit.toDouble()))
      assertThat(before).isEqualTo(FaultSpec())
      assertThat(after.services).isEmpty()
      assertThat(after.docker).isEqualTo(mapOf("api" to mapOf<String, Any?>("cpus" to values["cpus"])))
    }
    assertThat(scenario.answer.partial).containsOnly("cpu_throttle")
  }

  @Test
  fun `the busy poll sets the worker's poll timeout to zero and nothing else`() {
    val scenario = ScenarioCatalog.byId.getValue("busy_poll")
    val spec = scenario.faults(Params(scenario.params(Random(1), ExerciseContext()), "14:05"))
    assertThat(spec.services).isEqualTo(mapOf("worker" to mapOf<String, Any?>("poll_ms" to 0)))
    assertThat(spec.docker).isEmpty()
    assertThat(scenario.cli).isNotEmpty()
  }

  @Test
  fun `the app lock runs between 75 and 90 percent utilization and its explanation does the M_M_1 arithmetic`() {
    val scenario = ScenarioCatalog.byId.getValue("app_lock")
    val random = Random(6)
    repeat(30) {
      val hold = scenario.params(random, ExerciseContext())["lock_ms"] as Int
      val rho = Baseline.CHECKOUTS_PER_SECOND * hold / Baseline.MILLIS_PER_SECOND
      assertThat(rho).isBetween(0.74, 0.91)
    }
    val params = Params(mapOf("lock_ms" to 60), "14:05")
    assertThat(scenario.faults(params).services).isEqualTo(mapOf("api" to mapOf<String, Any?>("lock_ms" to 60)))
    val explain = scenario.explain(params)
    // 15/s × 60 ms = 90%: wait 0.9 / 0.1 × 60 = 540 ms, at the lock 600 ms, p99 4.6 × 600 ms ≈ 2.8 s, 15 × 0.54 ≈ 8.1 waiting.
    assertThat(explain.contains("= 90% of the time")).isTrue()
    assertThat(explain.contains("≈ 540 ms")).isTrue()
    assertThat(explain.contains("≈ 600 ms")).isTrue()
    assertThat(explain.contains("about 2.8 s")).isTrue()
    assertThat(explain.contains("≈ 8.1")).isTrue()
  }

  @Test
  fun `the unexplained win hides pricing errors behind api's fallback`() {
    val scenario = ScenarioCatalog.byId.getValue("unexplained_win")
    val spec = scenario.faults(Params(scenario.params(Random(1), ExerciseContext()), "14:05"))
    assertThat(spec.services.getValue("api")).isEqualTo(mapOf<String, Any?>("price_fallback" to true))
    assertThat(spec.services.getValue("pricing")["error_rate"]).isEqualTo(1.0)
    assertThat(scenario.answer).isEqualTo(ScenarioCatalog.byId.getValue("downstream_errors").answer)
  }

  @Test
  fun `the fork decoy pairs a harmless fork storm with the cache miss and shares its answer`() {
    val scenario = ScenarioCatalog.byId.getValue("fork_decoy")
    val random = Random(8)
    repeat(20) {
      val spec = scenario.faults(Params(scenario.params(random, ExerciseContext()), "14:05"))
      assertThat(spec.services.keys).containsOnly("api", "noisy")
      assertThat(spec.services.getValue("noisy")["fork_rps"] as Int).isBetween(100, 200)
      assertThat(spec.services.getValue("api")["cache_ttl_ms"] as Int).isBetween(100, 400)
    }
    assertThat(scenario.answer).isEqualTo(ScenarioCatalog.byId.getValue("cache_miss").answer)
    assertThat(scenario.decoy).isEqualTo(0.5)
  }

  private companion object {
    const val MIN_TOOLBOX_SCENARIOS = 11
    const val COMMENT = "   # "
    val TOOLBOX_CORE = listOf(
      "cpu_throttle", "noisy_neighbor", "single_thread_cpu", "pricing_cpu", "oom", "slow_leak", "pool_hold", "pool_shrunk", "db_lock", "consumer_backlog", "n_plus_one"
    )
  }
}

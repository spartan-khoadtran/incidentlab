package com.c0x12c.incidentlab.labctl.scenario

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
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
import com.c0x12c.incidentlab.labctl.scenario.catalog.DiskScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.DownstreamScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.EdgeScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.JvmScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.LoadScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.ProfilingScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.NetworkScenarios
import kotlin.random.Random
import org.junit.jupiter.api.Test

class ScenarioCatalogTest {

  private val natures = Answers.NATURES.map { it.first }.toSet()
  private val locations = Answers.LOCATIONS.map { it.first }.toSet()
  private val signals = Answers.SIGNALS.map { it.first }.toSet()
  private val causes = Answers.CAUSES.map { it.first }.toSet()
  private val layers = listOf(
    LoadScenarios.all, EdgeScenarios.all, AppScenarios.all, DatabaseScenarios.all, DownstreamScenarios.all, AsyncScenarios.all, ContainerHostScenarios.all,
    ProfilingScenarios.all,
    NetworkScenarios.all,
    DiskScenarios.all,
    JvmScenarios.all
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
    assertThat(scenariosPerCause.getValue("downstream_slow")).isEqualTo(4)
    assertThat(scenariosPerCause.getValue("lb_no_healthy")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("global_load")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("single_thread_cpu")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("client_timeout")).isEqualTo(2)
    assertThat(scenariosPerCause.getValue("cache_miss")).isEqualTo(2)
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
        assertThat(spec.docker.all { (container, settings) -> settings.keys.all { it in Services.BASE_DOCKER.getValue(container) || (it == "netem" && container in Services.NETEM_CONTAINERS) } }).isTrue()
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
  fun `the disk scenarios drive noisy, pgobserver and the postgres write IOPS`() {
    val random = Random(9)
    val wal = ScenarioCatalog.byId.getValue("wal_fsync_contention")
    val walSpec = wal.faults(Params(wal.params(random, ExerciseContext()), "14:05"))
    assertThat(walSpec.services.keys).containsOnly("noisy")
    assertThat((walSpec.services.getValue("noisy")["fsync_mb_per_s"] as Int)).isBetween(40, 120)

    val bursts = ScenarioCatalog.byId.getValue("checkpoint_bursts")
    val pg = bursts.faults(Params(bursts.params(random, ExerciseContext()), "14:05")).services.getValue("pgobserver")
    assertThat(pg["checkpoint_timeout_s"]).isEqualTo(30)
    assertThat(pg["checkpoint_completion_target"]).isEqualTo(0.1)
    assertThat(pg.containsKey("max_wal_size_mb")).isFalse()
    assertThat((pg["stats_rows_per_s"] as Int)).isBetween(2000, 3000)

    val iops = ScenarioCatalog.byId.getValue("iops_limit")
    val iopsSpec = iops.faults(Params(iops.params(random, ExerciseContext()), "14:05"))
    assertThat(iopsSpec.services).isEmpty()
    assertThat((iopsSpec.docker.getValue("postgres")["write_iops"] as Int)).isBetween(40, 60)
    assertThat(iops.answer.partial).containsOnly("disk_io_contention")
    assertThat("disk_full" in Answers.DISTRACTORS).isTrue()
  }

  @Test
  fun `cli entries are toolbox commands that say what to read`() {
    val entries = ScenarioCatalog.all.flatMap { it.cli }
    assertThat(entries.all { it.contains("   # ") && !it.contains("docker exec") && !it.contains("docker compose") && !it.contains("\${") }).isTrue()
    assertThat(entries.filter { it.contains("pgrep") }).isEmpty()
    val services = entries.flatMap { line -> Regex("""\$\(pid ([a-z]+)\)""").findAll(line).map { it.groupValues[1] }.toList() }.toSet()
    assertThat(services.isNotEmpty() && services.all { it in Services.ENDPOINTS || it == "postgres" }).isTrue()
  }

  @Test
  fun `the checkpoint explanation works out the dirty pages and the burst window`() {
    val scenario = ScenarioCatalog.byId.getValue("checkpoint_bursts")
    val explain = scenario.explain(Params(mapOf("rows" to 2500), "14:05"))

    // 2500 × 30 = 75000 updates; 16410 × (1 − e^(−75000/16410)) = 16240 pages = 126.9 MB; 30 × 0.1 = 3 s → 42.3 MB/s
    assertThat(explain.contains("75000 updates")).isTrue()
    assertThat(explain.contains("16240 pages (126.9 MB)")).isTrue()
    assertThat(explain.contains("= 3 s (about 42.3 MB/s)")).isTrue()
    val pgobserver = scenario.faults(Params(mapOf("rows" to 2500), "14:05")).services.getValue("pgobserver")
    assertThat(pgobserver.keys).containsOnly("checkpoint_timeout_s", "checkpoint_completion_target", "stats_rows_per_s")
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
  fun `the oom leak fills api's headroom in under a minute and the slow leak takes half an hour or more`() {
    val headroom = ContainerHostScenarios.LEAK_HEADROOM_MB
    val oom = ScenarioCatalog.byId.getValue("oom")
    val slow = ScenarioCatalog.byId.getValue("slow_leak")
    val random = Random(3)
    repeat(30) {
      val fast = oom.params(random, ExerciseContext())["leak"] as Int
      val secondsToLimit = headroom / (fast * Baseline.BASE_RPS / ContainerHostScenarios.KB_PER_MB)
      assertThat(secondsToLimit).isBetween(30.0, 50.0)
      val slowKb = slow.params(random, ExerciseContext())["leak"] as Int
      val minutesToLimit = headroom / (slowKb * Baseline.BASE_RPS * Baseline.SECONDS_PER_MINUTE / ContainerHostScenarios.KB_PER_MB)
      assertThat(minutesToLimit).isBetween(30.0, 50.0)
    }
    // 150 KB × 50/s = 7.3 MB/s, 280 / 7.3 = 38 s; 2 KB × 50 × 60 = 5.9 MB/min, 280 / 5.9 = 48 min
    assertThat(oom.explain(Params(mapOf("leak" to 150), "14:05")).contains("about 7.3 MB per second")).isTrue()
    assertThat(oom.explain(Params(mapOf("leak" to 150), "14:05")).contains("about 38 seconds")).isTrue()
    assertThat(slow.explain(Params(mapOf("leak" to 2), "14:05")).contains("about 48 minutes")).isTrue()
    assertThat(oom.explain(Params(mapOf("leak" to 150), "14:05")).contains("768 MB")).isTrue()
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
  fun `the unexplained win slows pricing first and hides it behind a short timeout and a fallback after the deploy`() {
    val scenario = ScenarioCatalog.byId.getValue("unexplained_win")
    val random = Random(1)
    repeat(20) {
      val values = scenario.params(random, ExerciseContext())
      val base = values["base"] as Int
      assertThat(base).isBetween(250, 400)
      // the fastest call after the 25% jitter still passes the timeout, so every call falls back
      assertThat(base * 0.75 > DownstreamScenarios.FALLBACK_TIMEOUT_MS).isTrue()
      val before = scenario.faults(Params(values, "14:05", elapsedSeconds = DownstreamScenarios.DEPLOY_AFTER_SECONDS - 1.0))
      val after = scenario.faults(Params(values, "14:05", elapsedSeconds = DownstreamScenarios.DEPLOY_AFTER_SECONDS.toDouble()))
      assertThat(before.services).isEqualTo(mapOf("pricing" to mapOf<String, Any?>("price_base_ms" to base)))
      assertThat(after.services.getValue("pricing")).isEqualTo(mapOf<String, Any?>("price_base_ms" to base))
      assertThat(after.services.getValue("api")).isEqualTo(mapOf<String, Any?>("price_fallback" to true, "pricing_timeout_s" to 0.15))
    }
    assertThat(scenario.answer.cause).isEqualTo("downstream_slow")
    assertThat(scenario.answer.partial).containsOnly("downstream_errors")
    assertThat(scenario.marker == null).isTrue()
    // 50 + 300 = 350 ms before, 50 + 150 = 200 ms after: 150 ms faster
    val explain = scenario.explain(Params(mapOf("base" to 300), "14:05"))
    assertThat(explain.contains("about 350 ms")).isTrue()
    assertThat(explain.contains("= 200 ms: about 150 ms faster")).isTrue()
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
    val measured = scenario.params(Random(1), ExerciseContext(productDetailSeconds = 0.018))
    assertThat(measured["query_ms"]).isEqualTo(18)
    val params = Params(mapOf("ttl" to 200, "fork_rps" to 150, "query_ms" to 18), "14:05")
    assertThat(scenario.ticket(params).contains("p99 did not move")).isTrue()
    for (id in listOf("fork_decoy", "cache_miss")) {
      val explain = ScenarioCatalog.byId.getValue(id).explain(params)
      assertThat(explain.contains("p50 jumps from under 1 ms to about 18 ms")).isTrue()
    }
  }

  @Test
  fun `the network scenarios shape api's traffic to the right target and keep the pool busy but not full`() {
    val random = Random(9)
    val targets = mapOf("packet_loss" to "pricing", "rtt_amplified" to "postgres", "no_keepalive" to "pricing")
    for ((id, target) in targets) {
      val scenario = ScenarioCatalog.byId.getValue(id)
      repeat(20) {
        val params = Params(scenario.params(random, ExerciseContext()), "14:05")
        val netem = scenario.faults(params).docker.getValue("api").getValue("netem") as Map<*, *>
        assertThat(netem["target"]).isEqualTo(target)
        assertThat(scenario.cli).isNotEmpty()
      }
    }
    val rtt = ScenarioCatalog.byId.getValue("rtt_amplified")
    repeat(200) {
      val params = Params(rtt.params(random, ExerciseContext()), "14:05")
      assertThat(NetworkScenarios.inUse(params)).isBetween(2.0, 3.0)
    }
    val keepalive = ScenarioCatalog.byId.getValue("no_keepalive")
    assertThat(keepalive.faults(Params(mapOf("delay" to 7), "14:05")).services).isEqualTo(mapOf("api" to mapOf<String, Any?>("pricing_keepalive" to false)))
  }

  @Test
  fun `the round trip arithmetic of rtt_amplified matches a hand computation`() {
    val params = Params(mapOf("delay" to 5, "k" to 6), "14:05")
    // a round trip costs 5 + 1.8 = 6.8 ms; 15/s x (50 + (2.5 x 6 + 3) x 6.8) ms = 15 x 0.1724 s = 2.586 connections
    assertThat(NetworkScenarios.inUse(params)).isCloseTo(2.586, 0.0001)
    val explain = ScenarioCatalog.byId.getValue("rtt_amplified").explain(params)
    assertThat(explain.contains("5 + 1.8 = 6.8 ms")).isTrue()
    assertThat(explain.contains("18 x 6.8 ms = 122 ms")).isTrue()
    assertThat(explain.contains("184 ms for a basket of 4")).isTrue()
    assertThat(explain.contains("about 2.6 of 4")).isTrue()
    // the live draw that filled the pool: 3 ms and 15 repeats give 15 x (50 + 40.5 x 4.8) ms = 3.67 connections
    assertThat(NetworkScenarios.inUse(Params(mapOf("delay" to 3, "k" to 15), "14:05")) > 3.6).isTrue()
  }

  private companion object {
    const val MIN_TOOLBOX_SCENARIOS = 11
    const val COMMENT = "   # "
    val TOOLBOX_CORE = listOf(
      "cpu_throttle", "noisy_neighbor", "single_thread_cpu", "pricing_cpu", "oom", "slow_leak", "pool_hold", "pool_shrunk", "db_lock", "consumer_backlog", "n_plus_one"
    )
  }

  @Test
  fun `the runtime scenarios drive only api's runtime keys`() {
    val random = Random(9)
    val keys = mapOf(
      "gc_pressure" to setOf("retain_mb", "churn_kb"),
      "vthread_pinning" to setOf("pin_db", "pin_hold_ms"),
      "sync_logging" to setOf("debug_log_lines")
    )
    for ((id, expected) in keys) {
      val scenario = ScenarioCatalog.byId.getValue(id)
      val spec = scenario.faults(Params(scenario.params(random, ExerciseContext()), "14:05"))
      assertThat(spec.services.keys).containsOnly("api")
      assertThat(spec.services.getValue("api").keys).isEqualTo(expected)
      assertThat(spec.docker).isEmpty()
    }
    assertThat(Answers.DISTRACTORS.contains("gc_pause")).isFalse()
  }

  @Test
  fun `the pinning hold keeps the two carriers 70 to 85 percent busy and the explanation does the M_M_2 arithmetic`() {
    assertThat(JvmScenarios.carrierDemand(JvmScenarios.PIN_HOLD_MIN_MS)).isBetween(0.70, 0.85)
    assertThat(JvmScenarios.carrierDemand(JvmScenarios.PIN_HOLD_MAX_MS)).isBetween(0.70, 0.85)
    // 15 checkouts/s × (50 + 50) ms / 2 carriers = 0.75; 2 × 0.75² / 1.75 = 0.643; 0.643 / (2 / 0.1 − 15) = 0.129 s
    assertThat(JvmScenarios.carrierDemand(50)).isEqualTo(0.75)
    assertThat(Baseline.round2(JvmScenarios.carrierBusyChance(50))).isEqualTo(0.64)
    assertThat(Baseline.round2(JvmScenarios.carrierWaitSeconds(50))).isEqualTo(0.13)
    val scenario = ScenarioCatalog.byId.getValue("vthread_pinning")
    val random = Random(4)
    repeat(50) {
      val hold = scenario.params(random, ExerciseContext())["hold"] as Int
      assertThat(JvmScenarios.carrierDemand(hold)).isBetween(0.70, 0.85)
    }
    val explain = scenario.explain(Params(mapOf("hold" to 50), "14:05"))
    assertThat(explain.contains("= 1.50 carrier-seconds per second")).isTrue()
    assertThat(explain.contains("75% busy")).isTrue()
    assertThat(explain.contains("= 64% of the time")).isTrue()
    assertThat(explain.contains("= 129 ms")).isTrue()
    assertThat(scenario.ticket(Params(mapOf("hold" to 50), "14:05")).contains("fell")).isFalse()
  }

  @Test
  fun `the logging band puts 0_19 to 0_3 cores of system time on api and its texts do the per-line arithmetic`() {
    assertThat(JvmScenarios.logSystemCores(JvmScenarios.MIN_LOG_LINES)).isBetween(0.19, 0.3)
    assertThat(JvmScenarios.logSystemCores(JvmScenarios.MAX_LOG_LINES)).isBetween(0.19, 0.3)
    val scenario = ScenarioCatalog.byId.getValue("sync_logging")
    val random = Random(2)
    repeat(30) {
      val lines = scenario.params(random, ExerciseContext())["lines"] as Int
      assertThat(lines).isBetween(JvmScenarios.MIN_LOG_LINES, JvmScenarios.MAX_LOG_LINES)
    }
    // 2000 × 50 = 100,000 lines/s; × 1.9 µs = 0.19 cores system, × 3.4 µs = 0.34 cores user; 2000 × 5.3 µs = 10.6 ms per request
    val params = Params(mapOf("lines" to 2000), "14:05")
    val explain = scenario.explain(params)
    assertThat(explain.contains("100000 lines/s")).isTrue()
    assertThat(explain.contains("0.34 cores of user time and 0.19 cores of system time")).isTrue()
    assertThat(explain.contains("≈ 11 ms of extra work")).isTrue()
    assertThat(scenario.ticket(params).contains("about 11 ms")).isTrue()
  }

  @Test
  fun `the GC store stays inside the calibrated band`() {
    val scenario = ScenarioCatalog.byId.getValue("gc_pressure")
    val random = Random(6)
    repeat(50) {
      val params = scenario.params(random, ExerciseContext())
      assertThat(params["retain"] as Int).isBetween(70, 85)
      assertThat(params["churn"] as Int).isBetween(48, 64)
    }
    val explain = scenario.explain(Params(mapOf("retain" to 80, "churn" to 64), "14:05"))
    // 80 MB / 20 bytes = 4.2 million objects; 50 × 64 KB = 3.1 MB/s; 80 / 3.125 = 26 s
    assertThat(explain.contains("4.2 million")).isTrue()
    assertThat(explain.contains("3.1 MB/s")).isTrue()
    assertThat(explain.contains("26 s")).isTrue()
    // 300 ms / 22 s = 1.4% of requests arrive during a pause
    assertThat(explain.contains("1.4% of requests")).isTrue()
    assertThat(scenario.evidence.none { it.contains("jvm_memory_max_bytes") }).isTrue()
  }
}

package com.c0x12c.incidentlab.dashboards

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

class BoardsTest {

  private val boards = Boards.all()

  private fun board(uid: String): Board = boards.single { it.uid == uid }

  private fun expressions(panels: List<Map<String, Any?>>): List<String> =
    panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.mapNotNull { (it as Map<*, *>)["expr"] as String? }

  @Test
  fun `the boards serialise with their uids, files and numbers in order`() {
    val mapper = ObjectMapper()
    boards.forEach { mapper.writeValueAsString(it.toJson()) }

    assertThat(boards.map { it.fileName }).isEqualTo(
      listOf("00-start.json", "01-red-layers.json", "02-use-app.json", "03-use-runtime.json", "04-use-host.json", "05-use-data.json")
    )
    assertThat(boards.map { it.number }).isEqualTo(listOf("00", "01", "02", "03", "04", "05"))
  }

  @Test
  fun `every row title carries its number, counted from 1 on each board`() {
    for (board in boards) {
      val titles = board.panels.filter { it["type"] == "row" }.map { it["title"] as String }
      assertThat(titles).isEqualTo(board.rows.mapIndexed { index, title -> "${index + 1} · $title" })
    }
  }

  @Test
  fun `the USE boards hold the ten resource rows, each once`() {
    val rows = boards.filter { it.uid.startsWith("use-") }.flatMap { it.rows }
    assertThat(rows).containsExactlyInAnyOrder(
      "Software resources inside api",
      "Kafka consumer (worker)",
      "Runtime (process)",
      "JVM (heap, GC, virtual threads)",
      "CPU profiles (Pyroscope)",
      "Container (cgroup)",
      "Host / VM (node-exporter)",
      "Network (per service)",
      "Postgres (server side)",
      "Disk (host, node-exporter)"
    )
  }

  @Test
  fun `every start panel links to another lab board and keeps the time range`() {
    val uids = boards.map { it.uid }.toSet()
    val panels = board("start").panels.filter { it["type"] == "timeseries" }
    val links = panels.map { (it["links"] as List<*>).single() as Map<*, *> }

    assertThat(panels.size).isEqualTo(16)
    assertThat(links.all { (it["url"] as String).endsWith("?\${__url_time_range}") }).isTrue()
    assertThat(links.map { (it["url"] as String).removePrefix("/d/").substringBefore('?') }.all { it in uids && it != "start" }).isTrue()
  }

  @Test
  fun `the start board shows numbers, never a verdict colour`() {
    val panels = board("start").panels.filter { it["type"] == "timeseries" }
    assertThat(panels.none { ((it["fieldConfig"] as Map<*, *>)["defaults"] as Map<*, *>).containsKey("thresholds") }).isTrue()
  }

  @Test
  fun `panels never overlap horizontally and rate intervals are expanded`() {
    for (board in boards) {
      val rows = board.panels.groupBy { (it["gridPos"] as Map<*, *>)["y"] }
      for ((_, row) in rows) {
        val sorted = row.map { it["gridPos"] as Map<*, *> }.sortedBy { it["x"] as Int }
        sorted.zipWithNext().forEach { (left, right) ->
          assertThat((left["x"] as Int) + (left["w"] as Int) <= (right["x"] as Int)).isTrue()
        }
      }
      assertThat(expressions(board.panels).none { it.contains("[RI]") }).isTrue()
    }
  }

  @Test
  fun `the CPU flame graphs query Pyroscope for the CPU profile of api and pricing`() {
    val flames = board("use-runtime").panels.filter { it["type"] == "flamegraph" }
    val targets = flames.map { (it["targets"] as List<*>).single() as Map<*, *> }

    assertThat(flames.all { it["datasource"] == Board.PROFILES_DATASOURCE }).isTrue()
    assertThat(targets.all { it["profileTypeId"] == "process_cpu:cpu:nanoseconds:cpu:nanoseconds" && it["queryType"] == "profile" }).isTrue()
    assertThat(targets.map { it["labelSelector"] }).isEqualTo(listOf("{service_name=\"api\"}", "{service_name=\"pricing\"}"))
  }

  @Test
  fun `the Postgres and disk board reads the disk waits from both sides`() {
    val data = board("use-data")
    assertThat(data.rows).isEqualTo(listOf("Postgres (server side)", "Disk (host, node-exporter)"))
    val expressions = expressions(data.panels)
    assertThat(listOf("pg_wait_events", "pg_wal_sync_seconds_total", "pg_checkpoints_total", "node_disk_io_time_weighted_seconds_total").all { name -> expressions.any { it.contains(name) } }).isTrue()
  }

  @Test
  fun `latency quantile targets ask for exemplars and rate targets do not`() {
    val targets = RedBoard.build().panels.filter { it["type"] == "timeseries" }.flatMap { (it["targets"] as? List<*>).orEmpty() }.map { it as Map<*, *> }
    val (quantiles, others) = targets.partition { (it["expr"] as String).startsWith("histogram_quantile(") }

    assertThat(quantiles.isNotEmpty() && quantiles.all { it["exemplar"] == true }).isTrue()
    assertThat(others.none { it["exemplar"] == true }).isTrue()
  }

  @Test
  fun `the software resources row ends with the inventory lock panels`() {
    val panels = board("use-app").panels
    val titles = panels.map { it["title"] as String }
    val next = titles.indexOf("2 · Kafka consumer (worker)")
    assertThat(titles.subList(next - 3, next)).isEqualTo(
      listOf("Inventory lock: wait p99", "Inventory lock: threads waiting (saturation)", "Inventory lock: utilization")
    )
    val expressions = expressions(panels.subList(next - 3, next))
    assertThat(expressions.any { it.contains("app_lock_wait_seconds_bucket") }).isTrue()
    assertThat(expressions.any { it.contains("app_lock_held_seconds_total") }).isTrue()
  }

  @Test
  fun `heatmaps read Prometheus buckets as they are`() {
    val heatmaps = boards.flatMap { it.panels }.filter { it["type"] == "heatmap" }
    assertThat(heatmaps.size).isEqualTo(2)
    for (panel in heatmaps) {
      val target = (panel["targets"] as List<*>).single() as Map<*, *>
      assertThat(target["format"]).isEqualTo("heatmap")
      val expr = target["expr"] as String
      assertThat(expr.startsWith("sum by (le) (increase(") && expr.endsWith("_seconds_bucket{job=\"api\"}[${Board.RATE_INTERVAL}]))")).isTrue()
      assertThat((panel["options"] as Map<*, *>)["calculate"]).isEqualTo(false)
    }
  }

  @Test
  fun `the JVM row has its eight panels`() {
    val panels = board("use-runtime").panels
    val rows = panels.withIndex().filter { it.value["type"] == "row" }
    val jvm = rows.indexOfFirst { it.value["title"] == "2 · JVM (heap, GC, virtual threads)" }
    assertThat(jvm >= 0).isTrue()
    val end = rows.getOrNull(jvm + 1)?.index ?: panels.size
    assertThat(end - rows[jvm].index - 1).isEqualTo(8)
  }
}

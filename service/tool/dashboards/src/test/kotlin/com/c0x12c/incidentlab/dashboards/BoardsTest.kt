package com.c0x12c.incidentlab.dashboards

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

class BoardsTest {

  @Test
  fun `both boards serialise and keep their uids`() {
    val mapper = ObjectMapper()
    val red = mapper.readTree(mapper.writeValueAsString(RedBoard.build().toJson()))
    val use = mapper.readTree(mapper.writeValueAsString(UseBoard.build().toJson()))

    assertThat(red["uid"].asText()).isEqualTo("red-layers")
    assertThat(use["uid"].asText()).isEqualTo("use-layers")
    assertThat(red["panels"].size()).isGreaterThan(30)
    assertThat(use["panels"].size()).isGreaterThan(25)
  }

  @Test
  fun `panels never overlap horizontally and rate intervals are expanded`() {
    val panels = RedBoard.build().panels
    val rows = panels.groupBy { (it["gridPos"] as Map<*, *>)["y"] }
    for ((_, row) in rows) {
      val sorted = row.map { it["gridPos"] as Map<*, *> }.sortedBy { it["x"] as Int }
      sorted.zipWithNext().forEach { (left, right) ->
        assertThat((left["x"] as Int) + (left["w"] as Int) <= (right["x"] as Int)).isTrue()
      }
    }
    val expressions = panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.mapNotNull { (it as Map<*, *>)["expr"] as String? }
    assertThat(expressions.none { it.contains("[RI]") }).isTrue()
  }

  @Test
  fun `the CPU flame graphs query Pyroscope for the CPU profile of api and pricing`() {
    val flames = UseBoard.build().panels.filter { it["type"] == "flamegraph" }
    val targets = flames.map { (it["targets"] as List<*>).single() as Map<*, *> }

    assertThat(flames.all { it["datasource"] == Board.PROFILES_DATASOURCE }).isTrue()
    assertThat(targets.all { it["profileTypeId"] == "process_cpu:cpu:nanoseconds:cpu:nanoseconds" && it["queryType"] == "profile" }).isTrue()
    assertThat(targets.map { it["labelSelector"] }).isEqualTo(listOf("{service_name=\"api\"}", "{service_name=\"pricing\"}"))
  }

  @Test
  fun `the USE board has the host disk row and the Postgres row reads disk waits`() {
    val panels = UseBoard.build().panels
    val rows = panels.filter { it["type"] == "row" }.map { it["title"] }
    val expressions = panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.mapNotNull { (it as Map<*, *>)["expr"] as String? }

    assertThat("Disk (host, node-exporter)" in rows).isTrue()
    assertThat(listOf("pg_wait_events", "pg_wal_sync_seconds_total", "pg_checkpoints_total", "node_disk_io_time_weighted_seconds_total").all { name -> expressions.any { it.contains(name) } }).isTrue()
    assertThat(expressions.none { it.contains("[RI]") }).isTrue()
  }

  @Test
  fun `latency quantile targets ask for exemplars and rate targets do not`() {
    val targets = RedBoard.build().panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.map { it as Map<*, *> }
    val (quantiles, others) = targets.partition { (it["expr"] as String).startsWith("histogram_quantile(") }

    assertThat(quantiles.isNotEmpty() && quantiles.all { it["exemplar"] == true }).isTrue()
    assertThat(others.none { it["exemplar"] == true }).isTrue()
  }

  @Test
  fun `the software resources row of the USE board ends with the inventory lock panels`() {
    val panels = UseBoard.build().panels
    val titles = panels.map { it["title"] as String }
    val row = titles.indexOf("Software resources inside api")
    val next = titles.indexOf("Runtime (process)")
    assertThat(titles.subList(next - 3, next)).isEqualTo(
      listOf("Inventory lock: wait p99", "Inventory lock: threads waiting (saturation)", "Inventory lock: utilization")
    )
    assertThat(row < next - 3).isTrue()
    val expressions = panels.subList(next - 3, next).flatMap { (it["targets"] as List<*>) }.mapNotNull { (it as Map<*, *>)["expr"] as String? }
    assertThat(expressions.any { it.contains("app_lock_wait_seconds_bucket") }).isTrue()
    assertThat(expressions.any { it.contains("app_lock_held_seconds_total") }).isTrue()
  }
}

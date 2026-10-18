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
    val expressions = panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.map { (it as Map<*, *>)["expr"] as String }
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
  fun `latency quantile targets ask for exemplars and rate targets do not`() {
    val targets = RedBoard.build().panels.flatMap { (it["targets"] as? List<*>).orEmpty() }.map { it as Map<*, *> }
    val (quantiles, others) = targets.partition { (it["expr"] as String).startsWith("histogram_quantile(") }

    assertThat(quantiles.isNotEmpty() && quantiles.all { it["exemplar"] == true }).isTrue()
    assertThat(others.none { it["exemplar"] == true }).isTrue()
  }
}

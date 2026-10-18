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
  fun `latency quantile targets ask for exemplars and rate targets do not`() {
    val targets = RedBoard.build().panels.filter { it["type"] == "timeseries" }.flatMap { (it["targets"] as? List<*>).orEmpty() }.map { it as Map<*, *> }
    val (quantiles, others) = targets.partition { (it["expr"] as String).startsWith("histogram_quantile(") }

    assertThat(quantiles.isNotEmpty() && quantiles.all { it["exemplar"] == true }).isTrue()
    assertThat(others.none { it["exemplar"] == true }).isTrue()
  }

  @Test
  fun `heatmaps read Prometheus buckets as they are`() {
    val heatmaps = (RedBoard.build().panels + UseBoard.build().panels).filter { it["type"] == "heatmap" }
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
  fun `the JVM row closes the USE board`() {
    val panels = UseBoard.build().panels
    val lastRow = panels.last { it["type"] == "row" }
    assertThat(lastRow["title"]).isEqualTo("JVM (heap, GC, virtual threads)")
    assertThat(panels.size - panels.indexOf(lastRow) - 1).isEqualTo(8)
  }
}

package com.c0x12c.incidentlab.dashboards

/** A Grafana dashboard assembled left to right, top to bottom, on the 24-column grid. */
class Board(
  private val uid: String,
  private val title: String,
  private val description: String
) {

  val panels = mutableListOf<Map<String, Any?>>()
  private var y = 0
  private var x = 0
  private var rowHeight = 0
  private var nextId = 1

  fun row(title: String) {
    if (x > 0) {
      y += rowHeight
      x = 0
    }
    panels.add(
      mapOf(
        "type" to "row",
        "title" to title,
        "collapsed" to false,
        "id" to id(),
        "gridPos" to mapOf("x" to 0, "y" to y, "w" to FULL_WIDTH, "h" to 1),
        "panels" to emptyList<Any>()
      )
    )
    y += 1
    rowHeight = 0
  }

  fun timeseries(
    title: String,
    targets: List<Pair<String, String>>,
    unit: String = "short",
    description: String = "",
    width: Int = DEFAULT_WIDTH,
    height: Int = DEFAULT_HEIGHT,
    minZero: Boolean = true,
    stack: Boolean = false
  ) {
    val defaults = mutableMapOf<String, Any?>(
      "unit" to unit,
      "custom" to mapOf(
        "lineWidth" to 1,
        "fillOpacity" to if (stack) STACK_FILL else 0,
        "showPoints" to "never",
        "spanNulls" to true,
        "stacking" to mapOf("mode" to if (stack) "normal" else "none")
      )
    )
    if (minZero) {
      defaults["min"] = 0
    }
    panels.add(
      mapOf(
        "type" to "timeseries",
        "title" to title,
        "description" to description,
        "id" to id(),
        "datasource" to DATASOURCE,
        "gridPos" to place(width, height),
        "targets" to targets.mapIndexed { index, (expr, legend) ->
          mapOf(
            "datasource" to DATASOURCE,
            "expr" to rate(expr),
            "legendFormat" to legend,
            "refId" to ('A' + index).toString(),
            "exemplar" to expr.contains(QUANTILE)
          )
        },
        "fieldConfig" to mapOf("defaults" to defaults, "overrides" to emptyList<Any>()),
        "options" to mapOf(
          "legend" to mapOf("displayMode" to "list", "placement" to "bottom", "showLegend" to true),
          "tooltip" to mapOf("mode" to "multi", "sort" to "desc")
        )
      )
    )
  }

  fun stat(title: String, expr: String, unit: String = "short", description: String = "", width: Int = STAT_WIDTH, height: Int = DEFAULT_HEIGHT) {
    panels.add(
      mapOf(
        "type" to "stat",
        "title" to title,
        "description" to description,
        "id" to id(),
        "datasource" to DATASOURCE,
        "gridPos" to place(width, height),
        "targets" to listOf(mapOf("datasource" to DATASOURCE, "expr" to rate(expr), "refId" to "A", "instant" to true)),
        "fieldConfig" to mapOf("defaults" to mapOf("unit" to unit), "overrides" to emptyList<Any>()),
        "options" to mapOf("reduceOptions" to mapOf("calcs" to listOf("lastNotNull")), "colorMode" to "none", "graphMode" to "none")
      )
    )
  }

  /** A Pyroscope flame graph for one label selector, summed over the dashboard's time range. */
  fun flamegraph(title: String, labelSelector: String, profileTypeId: String, description: String = "", width: Int = DEFAULT_WIDTH, height: Int = FLAME_HEIGHT) {
    panels.add(
      mapOf(
        "type" to "flamegraph",
        "title" to title,
        "description" to description,
        "id" to id(),
        "datasource" to PROFILES_DATASOURCE,
        "gridPos" to place(width, height),
        "targets" to listOf(
          mapOf(
            "datasource" to PROFILES_DATASOURCE,
            "refId" to "A",
            "queryType" to "profile",
            "profileTypeId" to profileTypeId,
            "labelSelector" to labelSelector,
            "groupBy" to emptyList<String>()
          )
        )
      )
    )
  }

  fun text(content: String, width: Int = FULL_WIDTH, height: Int = TEXT_HEIGHT) {
    panels.add(
      mapOf(
        "type" to "text",
        "title" to "",
        "id" to id(),
        "gridPos" to place(width, height),
        "options" to mapOf("mode" to "markdown", "content" to content)
      )
    )
  }

  fun toJson(): Map<String, Any?> =
    mapOf(
      "uid" to uid,
      "title" to title,
      "description" to description,
      "tags" to listOf(TAG),
      "timezone" to "browser",
      "schemaVersion" to SCHEMA_VERSION,
      "version" to 1,
      "editable" to true,
      "refresh" to "5s",
      "time" to mapOf("from" to "now-30m", "to" to "now"),
      "links" to listOf(
        mapOf("type" to "dashboards", "tags" to listOf(TAG), "title" to "Lab", "asDropdown" to false, "includeVars" to false, "keepTime" to true)
      ),
      "annotations" to mapOf(
        "list" to listOf(
          mapOf(
            "builtIn" to 1,
            "datasource" to GRAFANA_DATASOURCE,
            "enable" to true,
            "hide" to true,
            "iconColor" to "rgba(0, 211, 255, 1)",
            "name" to "Annotations & Alerts",
            "type" to "dashboard"
          ),
          mapOf(
            "datasource" to GRAFANA_DATASOURCE,
            "enable" to true,
            "hide" to false,
            "iconColor" to "orange",
            "name" to "Lab: deploy / config change",
            "target" to mapOf("type" to "tags", "tags" to listOf(TAG), "limit" to ANNOTATION_LIMIT, "matchAny" to true)
          )
        )
      ),
      "templating" to mapOf("list" to emptyList<Any>()),
      "panels" to panels
    )

  private fun id(): Int = nextId++

  private fun place(width: Int, height: Int): Map<String, Int> {
    if (x + width > FULL_WIDTH) {
      y += rowHeight
      x = 0
      rowHeight = 0
    }
    val position = mapOf("x" to x, "y" to y, "w" to width, "h" to height)
    x += width
    rowHeight = maxOf(rowHeight, height)
    return position
  }

  companion object {
    const val FULL_WIDTH = 24
    const val DEFAULT_WIDTH = 8
    const val STAT_WIDTH = 4
    const val DEFAULT_HEIGHT = 8
    const val TEXT_HEIGHT = 3
    const val FLAME_HEIGHT = 12
    const val CPU_PROFILE = "process_cpu:cpu:nanoseconds:cpu:nanoseconds"
    const val STACK_FILL = 10
    const val SCHEMA_VERSION = 39
    const val ANNOTATION_LIMIT = 100
    const val TAG = "lab"
    const val RATE_INTERVAL = "\$__rate_interval"
    private const val QUANTILE = "histogram_quantile("
    val DATASOURCE = mapOf("type" to "prometheus", "uid" to "prometheus")
    val GRAFANA_DATASOURCE = mapOf("type" to "grafana", "uid" to "-- Grafana --")
    val PROFILES_DATASOURCE = mapOf("type" to "grafana-pyroscope-datasource", "uid" to "pyroscope")

    /** `[RI]` in an expression becomes Grafana's rate interval. */
    fun rate(expr: String): String = expr.replace("[RI]", "[$RATE_INTERVAL]")

    fun quantile(p: Double, metric: String, by: String = "", selector: String = ""): String {
      val labels = if (by.isNotEmpty()) "le, $by" else "le"
      return "histogram_quantile($p, sum by ($labels) (rate(${metric}_bucket$selector[RI])))"
    }
  }
}

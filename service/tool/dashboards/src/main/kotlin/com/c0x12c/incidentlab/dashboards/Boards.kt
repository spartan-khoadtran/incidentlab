package com.c0x12c.incidentlab.dashboards

/** Every lab dashboard, in the order Grafana lists them. */
object Boards {

  fun all(): List<Board> {
    val red = RedBoard.build()
    val app = AppBoard.build()
    val runtime = RuntimeBoard.build()
    val host = HostBoard.build()
    val data = DataBoard.build()
    return listOf(StartBoard.build(red, app, runtime, host, data), red, app, runtime, host, data)
  }
}

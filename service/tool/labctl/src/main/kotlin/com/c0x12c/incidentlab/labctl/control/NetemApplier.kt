package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.logging.Logging
import com.c0x12c.logging.warn
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/**
 * Shapes a container's traffic per destination by running the netem script inside it. A scenario
 * puts `netem` under its container settings as `{target, delay_ms, loss_pct}` (or a list of them);
 * no `netem` means clear. The script is idempotent, and the last command is also kept here so an
 * unchanged tick costs no exec at all. It is repeated once a minute in case the container restarted.
 * Only [containers] are touched: tc needs NET_ADMIN, which compose grants to api alone.
 */
class NetemApplier(
  private val exec: ContainerExec,
  private val clock: Clock = Clock.systemUTC(),
  private val resync: Duration = Duration.ofMinutes(1),
  private val containers: Set<String> = Services.NETEM_CONTAINERS
) : Applier, Logging {

  override val dockerKeys: Set<String> = setOf(NETEM)

  private val applied = mutableMapOf<String, Applied>()
  private val lastError = mutableMapOf<String, String?>()

  override fun apply(desired: Desired) {
    for (container in containers) {
      val command = command(desired.docker[container]?.get(NETEM))
      val now = clock.millis()
      val last = applied[container]
      if (last != null && last.command == command && now - last.at < resync.toMillis()) {
        continue
      }
      try {
        exec.exec(container, command)
        applied[container] = Applied(command, now)
        lastError.remove(container)
      } catch (e: Exception) {
        applied.remove(container)
        if (lastError.put(container, e.message) != e.message) {
          warn { "netem $container: ${e.message}" }
        }
      }
    }
  }

  private class Applied(val command: List<String>, val at: Long)

  companion object {
    const val SCRIPT = "/run/netem.sh"
    const val NETEM = "netem"

    /** `netem.sh apply host delay loss [host delay loss]...`, or `netem.sh clear` when nothing is asked for. */
    fun command(netem: Any?): List<String> {
      val targets = when (netem) {
        is Map<*, *> -> listOf(netem)
        is List<*> -> netem.filterIsInstance<Map<*, *>>()
        else -> emptyList()
      }
      if (targets.isEmpty()) {
        return listOf(SCRIPT, "clear")
      }
      return listOf(SCRIPT, "apply") + targets.flatMap { listOf(it["target"].toString(), plain(it["delay_ms"]), plain(it["loss_pct"])) }
    }

    private fun plain(value: Any?): String = BigDecimal((value as? Number ?: 0).toString()).stripTrailingZeros().toPlainString()
  }
}

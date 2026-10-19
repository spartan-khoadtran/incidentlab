package com.c0x12c.incidentlab.labctl.exercise

import com.c0x12c.incidentlab.labctl.Terminal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The few text helpers the exercise modes share. */
object Format {

  fun banner(terminal: Terminal, text: String) {
    terminal.out()
    terminal.out("=".repeat(BANNER_WIDTH))
    terminal.out(text)
    terminal.out("=".repeat(BANNER_WIDTH))
  }

  fun plain(value: Double): String = if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

  fun hhmm(epochSeconds: Double, zone: ZoneId): String = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).format(HOUR_MINUTE)

  fun rps(value: Double?): String = value?.let { "${it.roundToInt()} rps" } ?: "-"

  fun millis(seconds: Double?): String = seconds?.let { "${(it * MILLIS_PER_SECOND).roundToInt()} ms" } ?: "-"

  fun percent(share: Double?): String = share?.let { String.format(Locale.ROOT, "%.1f%%", it * PERCENT) } ?: "-"

  private const val BANNER_WIDTH = 72
  private const val MILLIS_PER_SECOND = 1_000.0
  private const val PERCENT = 100.0
  private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}

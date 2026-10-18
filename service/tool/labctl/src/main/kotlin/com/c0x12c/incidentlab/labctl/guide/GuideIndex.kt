package com.c0x12c.incidentlab.labctl.guide

/** One part of a `doc` pointer and the docs file that holds its section, null when no file does. */
data class GuidePointer(
  val pointer: String,
  val file: String?
)

/**
 * Which file under docs/ owns each `## ` section. A `doc` pointer names a section and a subsection,
 * so this is how a pointer finds its file. A section title must be unique across the files.
 */
object GuideIndex {

  val fileOf: Map<String, String> =
    listOf(
      "Overview",
      "RED method in detail",
      "USE method in detail",
      "RED at many layers",
      "USE at many layers",
      "Linking RED and USE",
      "Combining with other methods",
      "In this lab",
      "Applying on Datadog",
      "End-to-end investigation example",
      "Cheat sheet"
    ).associateWith { "investigation-guide.md" } +
      listOf(
        "Benchmarking mistakes",
        "Open and closed load models"
      ).associateWith { "benchmarking.md" } +
      listOf(
        "Capacity from a ramp test",
        "Closed-loop clients in this lab"
      ).associateWith { "capacity.md" } +
      listOf(
        "CPU shares and quotas",
        "CPU busy with no work",
        "Off-CPU time"
      ).associateWith { "cpu.md" } +
      listOf(
        "Disk math",
        "Postgres and the disk",
        "Disk panels in this lab"
      ).associateWith { "disk.md" } +
      listOf(
        "Statistics for latency",
        "Latency time scales",
        "Modeling and scaling laws"
      ).associateWith { "foundations.md" } +
      listOf(
        "Methods for the root cause",
        "Working an incident"
      ).associateWith { "methods.md" } +
      listOf(
        "Reading monitoring data",
        "PromQL traps",
        "Metrics that lie"
      ).associateWith { "monitoring.md" } +
      listOf(
        "Network math",
        "TCP behaviour you feel in p99",
        "Network panels in this lab"
      ).associateWith { "network.md" } +
      listOf(
        "CPU profiling",
        "Off-CPU profiling",
        "Profiles in this lab"
      ).associateWith { "profiling.md" } +
      listOf(
        "Garbage collection",
        "Virtual threads",
        "System time"
      ).associateWith { "runtime.md" } +
      listOf(
        "The 60-second checklist",
        "Crisis tools",
        "The toolbox in this lab"
      ).associateWith { "toolbox.md" }

  /** The pointer with its file in front of each part, or one file in front of the whole pointer when every part shares it. */
  fun where(doc: String): String {
    val parts = doc.split("; ")
    val files = parts.map { part -> fileOf[section(part)]?.let { "$DIRECTORY/$it" } ?: DIRECTORY }
    return if (files.distinct().size == 1) "${files.first()}: $doc" else parts.indices.joinToString("; ") { "${files[it]}: ${parts[it]}" }
  }

  fun pointers(doc: String): List<GuidePointer> = doc.split("; ").map { GuidePointer(it, fileOf[section(it)]) }

  private fun section(pointer: String): String = pointer.replace(NOTE, "").substringBefore(" > ").trim()

  private const val DIRECTORY = "docs"
  private val NOTE = Regex("""\s*\([^)]*\)$""")
}

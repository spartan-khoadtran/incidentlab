package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.c0x12c.incidentlab.labctl.state.ActiveExercise
import com.c0x12c.incidentlab.labctl.state.LabState
import com.c0x12c.incidentlab.labctl.state.StateStore
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlDaemonTest {

  @Test
  fun `one tick hands the desired state to every applier`(@TempDir dir: Path) {
    val store = StateStore(dir.resolve("state.json"))
    store.save(LabState(active = ActiveExercise(id = "cpu_throttle", params = mapOf("cpus" to 0.3), started = 0.0, n = 1)))
    val seen = mutableListOf<Desired>()
    val daemon = ControlDaemon(store, listOf(Applier { seen += it }, Applier { seen += it }))

    daemon.applyOnce()

    assertThat(seen.size).isEqualTo(2)
    assertThat(seen.map { it.docker.getValue("api") }.toSet()).isEqualTo(setOf(mapOf("cpus" to 0.3)))
  }

  @Test
  fun `at start the daemon writes the settings its appliers apply beside the state file`(@TempDir dir: Path) {
    val stateFile = dir.resolve("state.json")
    val capabilities = CapabilitiesFile.beside(stateFile)
    val netem = NetemApplier({ _, _ -> "" })
    val cgroups = CgroupApplier({ null }, dir, "sda")
    val cpu = object : Applier {
      override val dockerKeys = setOf(ContainerCpuApplier.CPUS)

      override fun apply(desired: Desired) {}
    }
    assertThat(capabilities.dockerKeys()).isNull()

    ControlDaemon(StateStore(stateFile), listOf(Applier { }, cpu, netem, cgroups), capabilities).publishCapabilities()

    assertThat(capabilities.dockerKeys()).isEqualTo(setOf("cpus", "netem", "write_iops"))
    assertThat(StateStore(stateFile).load()).isEqualTo(LabState())
  }
}

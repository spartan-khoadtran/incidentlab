package com.c0x12c.incidentlab.labctl.control

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test

class DockerControlTest {

  @Test
  fun `stdout and stderr frames are joined in order`() {
    val stream = frames(1 to "netem: pricing=172.18.0.5 delay=0ms loss=2%;\n", 2 to "warning\n", 1 to "done\n")

    assertThat(DockerControl.demux(stream)).isEqualTo("netem: pricing=172.18.0.5 delay=0ms loss=2%;\nwarning\ndone\n")
  }

  @Test
  fun `bytes that are not frames come back as they are`() {
    val raw = "plain tty output\n".toByteArray()

    assertThat(DockerControl.demux(raw)).isEqualTo("plain tty output\n")
    assertThat(DockerControl.demux(ByteArray(0))).isEqualTo("")
  }

  @Test
  fun `a truncated frame falls back to the raw bytes`() {
    val whole = frames(1 to "abcdef")
    val cut = whole.copyOf(whole.size - 2)

    assertThat(DockerControl.demux(cut)).isEqualTo(cut.toString(Charsets.UTF_8))
  }

  private fun frames(vararg parts: Pair<Int, String>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((type, text) in parts) {
      val payload = text.toByteArray()
      out.write(ByteBuffer.allocate(8).put(type.toByte()).put(0).put(0).put(0).putInt(payload.size).array())
      out.write(payload)
    }
    return out.toByteArray()
  }
}

package com.c0x12c.incidentlab.labctl.guide

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GuideIndexTest {

  @Test
  fun `a pointer whose parts share one file gets that file once, in front`() {
    assertThat(GuideIndex.where("RED method in detail > Rate; Linking RED and USE > Little's Law"))
      .isEqualTo("docs/investigation-guide.md: RED method in detail > Rate; Linking RED and USE > Little's Law")
    assertThat(GuideIndex.where("End-to-end investigation example (compare with it)"))
      .isEqualTo("docs/investigation-guide.md: End-to-end investigation example (compare with it)")
  }

  @Test
  fun `parts from different files each get their own file, and an unknown section falls back to the docs folder`() {
    assertThat(GuideIndex.where("Overview; Nowhere > Rate")).isEqualTo("docs/investigation-guide.md: Overview; docs: Nowhere > Rate")
  }
}

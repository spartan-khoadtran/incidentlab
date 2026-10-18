package com.c0x12c.incidentlab.infra.faults

import com.c0x12c.incidentlab.faults.FaultSnapshot
import com.c0x12c.incidentlab.faults.Faults
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put

// The door the lab controller pushes faults through. PUT replaces every override at once, so the
// controller's desired state is the whole truth every 3 seconds; a hand-made PUT lasts that long.
@Controller("/admin")
class FaultsController(
  private val faults: Faults
) {

  @Get("/faults")
  fun current(): FaultSnapshot = faults.snapshot()

  @Put("/faults")
  fun replace(@Body overrides: Map<String, Any?>?): FaultSnapshot {
    faults.replace(overrides ?: emptyMap())
    return faults.snapshot()
  }

  @Post("/reset")
  fun reset(): FaultSnapshot {
    faults.reset()
    return faults.snapshot()
  }
}

package com.c0x12c.incidentlab.shared.exception

// Wire shape of an error body, built from a ClientException at the edge.
data class ErrorResponse(
  val code: String,
  val message: String,
  val status: Int
)

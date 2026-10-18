package com.c0x12c.incidentlab.shared.exception

import io.micronaut.http.HttpStatus

// One open base for every domain error. code is the stable machine token, status is what the
// edge answers with, message is what the body carries.
open class ClientException(
  val code: String,
  override val message: String,
  val status: HttpStatus = HttpStatus.BAD_REQUEST
) : RuntimeException(message)

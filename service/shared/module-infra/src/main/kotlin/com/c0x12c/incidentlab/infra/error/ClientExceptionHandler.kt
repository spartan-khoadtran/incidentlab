package com.c0x12c.incidentlab.infra.error

import com.c0x12c.incidentlab.shared.exception.ClientException
import com.c0x12c.incidentlab.shared.exception.ErrorResponse
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton

// Renders a thrown ClientException as a plain ErrorResponse body with the status it carries.
@Produces
@Singleton
class ClientExceptionHandler : ExceptionHandler<ClientException, MutableHttpResponse<*>> {

  override fun handle(request: HttpRequest<*>, exception: ClientException): MutableHttpResponse<*> =
    HttpResponse
      .status<Any>(exception.status)
      .body(ErrorResponse(code = exception.code, message = exception.message, status = exception.status.code))
}

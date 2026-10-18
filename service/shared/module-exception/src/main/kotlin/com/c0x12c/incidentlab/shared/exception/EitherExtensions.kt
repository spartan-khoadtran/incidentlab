package com.c0x12c.incidentlab.shared.exception

import arrow.core.Either

// Controllers call this on a manager result: unwrap the value, or throw the ClientException the
// edge exception handler turns into an ErrorResponse.
fun <T> Either<ClientException, T>.throwOrValue(): T = fold({ throw it }, { it })

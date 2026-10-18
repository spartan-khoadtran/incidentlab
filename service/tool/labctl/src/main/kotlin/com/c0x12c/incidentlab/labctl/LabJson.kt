package com.c0x12c.incidentlab.labctl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule

object LabJson {
  val mapper: ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .enable(SerializationFeature.INDENT_OUTPUT)
}

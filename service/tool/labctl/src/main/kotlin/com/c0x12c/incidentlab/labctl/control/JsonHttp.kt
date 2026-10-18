package com.c0x12c.incidentlab.labctl.control

import com.c0x12c.incidentlab.labctl.LabJson
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.time.Duration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The few JSON calls the controller makes: service admin endpoints, Grafana, Prometheus. */
class JsonHttp(timeout: Duration = Duration.ofSeconds(2)) {

  private val client = OkHttpClient.Builder()
    .connectTimeout(timeout)
    .readTimeout(timeout)
    .callTimeout(timeout)
    .build()

  fun get(url: String): JsonNode = exchange(Request.Builder().url(url).get().build())

  fun put(url: String, body: Any): JsonNode = exchange(Request.Builder().url(url).put(encode(body)).build())

  fun post(url: String, body: Any): JsonNode = exchange(Request.Builder().url(url).post(encode(body)).build())

  fun delete(url: String): JsonNode = exchange(Request.Builder().url(url).delete().build())

  private fun encode(body: Any) = LabJson.mapper.writeValueAsString(body).toRequestBody(JSON)

  private fun exchange(request: Request): JsonNode =
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("${request.method} ${request.url} -> ${response.code}")
      }
      val text = response.body.string()
      if (text.isBlank()) LabJson.mapper.nullNode() else LabJson.mapper.readTree(text)
    }

  private companion object {
    val JSON = "application/json".toMediaType()
  }
}

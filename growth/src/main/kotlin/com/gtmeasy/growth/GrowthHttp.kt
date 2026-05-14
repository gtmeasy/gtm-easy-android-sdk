package com.gtmeasy.growth

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client interface so tests can substitute a fake transport without
 * pulling in OkHttp or Ktor as a runtime dependency.
 */
interface GrowthHttpClient {
    suspend fun post(url: String, headers: Map<String, String>, body: String): GrowthHttpResponse
}

data class GrowthHttpResponse(val status: Int, val body: String)

internal class UrlConnectionHttpClient(private val timeoutMs: Long) : GrowthHttpClient {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GrowthHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            GrowthHttpResponse(connection.responseCode, response)
        } finally {
            connection.disconnect()
        }
    }
}

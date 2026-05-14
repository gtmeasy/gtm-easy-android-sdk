package com.gtmeasy.growth

import android.content.Context

/**
 * Immutable configuration for [GrowthAnalytics]. Create once per process at app start.
 *
 * On Android, pass an [android.content.Context] so the SDK can persist the anonymous id
 * via `SharedPreferences`. On the JVM, leave [context] null and an in-memory anonymous
 * id will be generated per process.
 */
data class GrowthAnalyticsConfiguration(
    val app: String,
    val endpoint: String,
    val writeKey: String,
    val environment: Environment = Environment.PRODUCTION,
    val context: Context? = null,
    /** Override the User-Agent header sent on ingest. */
    val userAgent: String? = null,
    /** HTTP connect + read timeout in milliseconds. */
    val timeoutMs: Long = 10_000,
) {
    enum class Environment(val wire: String) {
        PRODUCTION("production"),
        STAGING("staging"),
        DEVELOPMENT("development"),
    }

    init {
        require(app.isNotBlank()) { "app must not be blank" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(writeKey.isNotBlank()) { "writeKey must not be blank" }
        require(timeoutMs in 1_000..60_000) { "timeoutMs must be between 1s and 60s" }
    }
}

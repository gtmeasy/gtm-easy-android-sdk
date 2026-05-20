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
    val writeKey: String,
    /**
     * Production ingest host. Override only when running against a self-hosted
     * GTM Easy deployment or a local development server.
     */
    val endpoint: String = DEFAULT_ENDPOINT,
    val environment: Environment = Environment.PRODUCTION,
    val context: Context? = null,
    /** Override the User-Agent header sent on ingest. */
    val userAgent: String? = null,
    /** HTTP connect + read timeout in milliseconds. */
    val timeoutMs: Long = 10_000,
    /**
     * When true, every identify / track is mirrored to [GrowthDebugSink]
     * before the network call. Subscribe to [GrowthDebugSink.events] from a
     * debug UI for a live feed.
     */
    val debug: Boolean = false,
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

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://www.gtmeasy.com"
    }
}


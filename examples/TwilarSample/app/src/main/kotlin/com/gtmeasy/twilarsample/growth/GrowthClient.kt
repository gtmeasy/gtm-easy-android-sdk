package com.gtmeasy.twilarsample.growth

import android.content.Context
import com.gtmeasy.growth.GrowthAnalytics
import com.gtmeasy.growth.GrowthAnalyticsConfiguration

/**
 * Singleton wrapper that builds the [GrowthAnalytics] instance once per
 * process. Real host apps would inject this through Hilt / Koin, but the
 * sample keeps it simple.
 *
 * The endpoint defaults to the LAN staging address used by the GTM Easy
 * monorepo (`http://192.168.3.241:3000`) so the sample is immediately
 * useful from a developer's emulator. Replace with `https://www.gtmeasy.com`
 * (the SDK default) for production.
 */
object GrowthClient {
    /** Multi-tenant `app` slug — the backend scopes events by this. */
    const val APP = "twilar"

    /** Write key from `gtmeasy.com → Settings → Write Keys`. Replace before shipping. */
    const val WRITE_KEY = "wk_sample_replace_me"

    /** LAN staging from `apps/web/docker-compose.staging.yml`. */
    const val ENDPOINT = "http://192.168.3.241:3000"

    @Volatile private var instance: GrowthAnalytics? = null

    fun init(applicationContext: Context): GrowthAnalytics {
        return instance ?: synchronized(this) {
            instance ?: GrowthAnalytics(
                GrowthAnalyticsConfiguration(
                    app = APP,
                    writeKey = WRITE_KEY,
                    endpoint = ENDPOINT,
                    environment = GrowthAnalyticsConfiguration.Environment.STAGING,
                    context = applicationContext,
                    debug = true,
                )
            ).also { instance = it }
        }
    }

    fun require(): GrowthAnalytics =
        instance ?: error("GrowthClient.init(context) must be called from Application.onCreate")
}

package com.gtmeasy.twilarsample

import android.app.Application
import com.gtmeasy.growth.GrowthLifecycleObserver
import com.gtmeasy.twilarsample.growth.GrowthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application bootstrap — initialises the Growth SDK and registers the
 * lifecycle observer so `app.first_open` (idempotent) and `app.opened`
 * automatically fan out on each cold start / foreground.
 */
class TwilarSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val analytics = GrowthClient.init(this)
        // Auto-instrumentation: fires app.first_open (once per install) and
        // app.opened on every activity resume.
        GrowthLifecycleObserver(analytics = analytics, context = this).register()
        // Also fan out app_opened on this very launch so the sample's
        // first-render console tail has something to show.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            runCatching { analytics.trackAppOpen() }
        }
    }
}

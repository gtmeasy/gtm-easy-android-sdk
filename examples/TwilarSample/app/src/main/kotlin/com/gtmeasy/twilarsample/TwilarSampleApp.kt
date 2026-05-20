package com.gtmeasy.twilarsample

import android.app.Application
import com.gtmeasy.growth.GrowthLifecycleObserver
import com.gtmeasy.twilarsample.growth.GrowthClient

/**
 * Application bootstrap — initialises the Growth SDK and registers the
 * lifecycle observer so `app.first_open` (UserDefaults-guarded, fires once
 * per install) and `app.opened` (fires on each foreground) automatically
 * fan out without manual help.
 */
class TwilarSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val analytics = GrowthClient.init(this)
        // GrowthLifecycleObserver owns cold-launch tracking. Manually
        // firing app.opened here would double-count the launch on this
        // process start.
        GrowthLifecycleObserver(analytics = analytics, context = this).register()
    }
}

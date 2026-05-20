package com.gtmeasy.growth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires `app.first_open` exactly once per install and `app.opened` on every
 * activity resume. Host app calls [register] from its `Application.onCreate`;
 * everything else is automatic.
 *
 * The first_open flag is stored in SharedPreferences so reinstalls reset it
 * (matches Apple's IDFV behavior and ad-platform expectations).
 */
class GrowthLifecycleObserver(
    private val analytics: GrowthAnalytics,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Application.ActivityLifecycleCallbacks {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var foregroundCount = 0

    fun register() {
        val app = (context.applicationContext as? Application) ?: return
        app.registerActivityLifecycleCallbacks(this)
        scope.launch { fireFirstOpenIfNeeded() }
    }

    fun unregister() {
        val app = (context.applicationContext as? Application) ?: return
        app.unregisterActivityLifecycleCallbacks(this)
    }

    private suspend fun fireFirstOpenIfNeeded() {
        if (prefs.getBoolean(KEY_FIRST_OPEN, false)) return
        prefs.edit()
            .putBoolean(KEY_FIRST_OPEN, true)
            .putLong(KEY_INSTALL_AT, System.currentTimeMillis())
            .apply()
        runCatching { analytics.trackFirstOpen() }
    }

    val installAt: Long?
        get() = prefs.getLong(KEY_INSTALL_AT, 0L).takeIf { it > 0L }

    // --- Application.ActivityLifecycleCallbacks ---

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (foregroundCount == 0) {
            scope.launch { runCatching { analytics.trackAppOpen() } }
        }
        foregroundCount++
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        foregroundCount = (foregroundCount - 1).coerceAtLeast(0)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val PREFS = "gtm_easy_growth_lifecycle"
        private const val KEY_FIRST_OPEN = "first_open_fired"
        private const val KEY_INSTALL_AT = "install_at_ms"
    }
}

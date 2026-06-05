package com.gtmeasy.growth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires the right install/lifecycle events with no per-event integration:
 * - `app.first_open` exactly once, only for a genuine fresh install,
 * - `app.updated` when the version/build changes between launches, or when the SDK first
 *   runs on a device that pre-existed install tracking (never counted as an install),
 * - `app.opened` on every foreground.
 *
 * Host app calls [register] from its `Application.onCreate`; everything else is automatic.
 * Classification is delegated to [GrowthInstallState] (backed by `SharedPreferences`), so
 * an app update never re-fires an install.
 *
 * To suppress the one-time adoption spike when an app with an existing user base first
 * ships the SDK, either pass a custom [InstallSignalProvider] or call
 * [markInstalledBeforeTracking] for users you already know are existing.
 */
class GrowthLifecycleObserver @JvmOverloads constructor(
    private val analytics: GrowthAnalytics,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val installSignalProvider: InstallSignalProvider = AndroidInstallSignalProvider(context),
    private val trackBuildChanges: Boolean = false,
) : Application.ActivityLifecycleCallbacks {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val installState = GrowthInstallState(SharedPrefsInstallStateStore(prefs))
    private var foregroundCount = 0
    // Resolved once the install/update classification has fired, so the first `app.opened`
    // is ordered AFTER `app.first_open` / `app.updated` instead of racing it.
    private val launchResolved = CompletableDeferred<Unit>()

    fun register() {
        // Only the main process may fire lifecycle events. `Application.onCreate` runs in
        // EVERY process (a `:remote` service, an early ContentProvider, etc.), and
        // SharedPreferences is not cross-process coherent — so a secondary process racing
        // `resolveLaunch` would independently read `first_open_fired=false` and double-fire
        // `app.first_open`. Guard here; secondary processes' opens/installs are meaningless.
        if (!isMainProcess()) return
        val app = (context.applicationContext as? Application) ?: return
        app.registerActivityLifecycleCallbacks(this)
        scope.launch { resolveAndFireLaunch() }
    }

    /** Fail OPEN: if the process name can't be resolved, proceed rather than silently drop
     *  real events from the genuine main process. The main process name is the app's
     *  `applicationInfo.processName` — which defaults to the package name, but honours a
     *  custom `android:process` on the `<application>` (e.g. ":main"); comparing against the
     *  raw package name would mis-classify such apps' main process as secondary and drop
     *  every lifecycle event. */
    private fun isMainProcess(): Boolean =
        isMainProcessName(currentProcessName(), context.applicationInfo.processName ?: context.packageName)

    private fun currentProcessName(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // Read argv[0] up to the first NUL (cmdline args are NUL-separated) — no NUL char
            // literal in source, which renders as a space in diffs and is easy to corrupt.
            File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).ifEmpty { null }
        }
    }.getOrNull()

    fun unregister() {
        val app = (context.applicationContext as? Application) ?: return
        app.unregisterActivityLifecycleCallbacks(this)
    }

    /**
     * Mark this install as pre-existing without firing `app.first_open`. Idempotent. Call
     * this in the release that first adds the SDK, for users you already know are existing
     * (signed-in, has local data), to avoid counting them as new installs.
     */
    fun markInstalledBeforeTracking() {
        installState.markInstalledBeforeTracking(appVersion(), buildNumber())
    }

    private suspend fun resolveAndFireLaunch() {
        try {
            val version = appVersion()
            val build = buildNumber()
            // The OS signal is only relevant on the first SDK run; skip it (and the
            // PackageManager call) once we've already classified this install.
            val signal = if (installState.firstOpenFired) {
                GrowthInstallSignal.UNKNOWN
            } else {
                installSignalProvider.priorInstallSignal()
            }
            val launch = installState.resolveLaunch(
                currentVersion = version,
                currentBuild = build,
                signal = signal,
                isProduction = analytics.environment == GrowthAnalyticsConfiguration.Environment.PRODUCTION,
                trackBuildChanges = trackBuildChanges,
            )
            fireLaunch(analytics, launch, version, build) { installState.persistBaseline(version, build) }
        } finally {
            launchResolved.complete(Unit)
        }
    }

    val installAt: Long? get() = installState.installAt

    // --- Application.ActivityLifecycleCallbacks ---

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (foregroundCount == 0) {
            scope.launch {
                // Order app.opened after the install/update classification.
                launchResolved.await()
                runCatching { analytics.trackAppOpen() }
            }
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

    private fun appVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private fun buildNumber(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString()
        else @Suppress("DEPRECATION") info.versionCode.toString()
    }.getOrNull()

    companion object {
        private const val PREFS = "gtm_easy_growth_lifecycle"

        /** Main process iff the running process name matches the app's main process name
         *  (`applicationInfo.processName`, normally the package). A named process is
         *  "<main>:suffix" and must NOT fire lifecycle events. A null running name
         *  (resolution failed) is treated as main so we never drop the real process. Pure +
         *  Context-free so the multi-process guard is unit-testable. */
        internal fun isMainProcessName(processName: String?, mainProcessName: String): Boolean =
            processName == null || processName == mainProcessName
    }
}

/**
 * Emit the analytics event for a resolved [GrowthLaunchType]. Extracted so the
 * classification→event mapping is unit-testable without an Android `Context`.
 */
internal suspend fun fireLaunch(
    analytics: GrowthAnalytics,
    launch: GrowthLaunchType,
    toVersion: String?,
    toBuild: String?,
    onRealUpdateSent: () -> Unit = {},
) {
    when (launch) {
        GrowthLaunchType.FreshInstall -> runCatching { analytics.trackFirstOpen() }
        is GrowthLaunchType.Update -> runCatching {
            analytics.trackAppUpdated(
                fromVersion = launch.fromVersion,
                fromBuild = launch.fromBuild,
                toVersion = toVersion,
                toBuild = toBuild,
                reason = launch.reason,
                isRealUpdate = launch.reason != GrowthUpdateReason.PRE_EXISTING_INSTALL,
            )
        }.onSuccess {
            // Advance the baseline only after a REAL update posts (at-least-once): if the send
            // above threw, the baseline is untouched so the next launch retries this update.
            // pre-existing already adopted its baseline in resolveLaunch (at-most-once, by design).
            if (launch.reason != GrowthUpdateReason.PRE_EXISTING_INSTALL) onRealUpdateSent()
        }
        GrowthLaunchType.Launch -> Unit
    }
}

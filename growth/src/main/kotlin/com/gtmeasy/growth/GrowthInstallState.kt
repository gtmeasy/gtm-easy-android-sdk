package com.gtmeasy.growth

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether the OS believes a build of this app existed on the device *before* the SDK
 * first ran. [UNKNOWN] is the safe default: it resolves to a fresh install so a genuine
 * acquisition is never under-counted.
 */
enum class GrowthInstallSignal { FRESH, EXISTED, UNKNOWN }

/** Why an `app.updated` event fired. Mirrors the `reason` property the server stores. */
enum class GrowthUpdateReason(val wire: String) {
    /** The version name (`versionName`) changed since the last run. */
    VERSION_CHANGE("version_change"),
    /** Only the build number (`versionCode`) changed — same version name. */
    BUILD_CHANGE("build_change"),
    /** First SDK run on a device that pre-existed install tracking (adoption / upgrade
     * from a pre-tracking version). Not a real version bump. */
    PRE_EXISTING_INSTALL("pre_existing_install"),
}

/** The classification of a single SDK launch, decided exactly once at lifecycle start. */
sealed interface GrowthLaunchType {
    /** Genuine fresh install → fire `app.first_open`. */
    object FreshInstall : GrowthLaunchType
    /** Version/build changed, or a pre-existing install adopted the SDK → `app.updated`. */
    data class Update(val reason: GrowthUpdateReason, val fromVersion: String?, val fromBuild: String?) : GrowthLaunchType
    /** Normal relaunch, no version change → nothing extra (just `app.opened`). */
    object Launch : GrowthLaunchType
}

/** Reports whether the app pre-existed install tracking on this device. */
fun interface InstallSignalProvider {
    fun priorInstallSignal(): GrowthInstallSignal
}

/** Minimal persistence abstraction so the decision logic is unit-testable without a real
 *  Android `SharedPreferences`. */
interface GrowthInstallStateStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * Owns the durable install/update bookkeeping and the pure decision that turns
 * (persisted state + current version + OS signal) into a [GrowthLaunchType]. All state is
 * read/written through an injected [GrowthInstallStateStore], so the decision is fully
 * testable with an in-memory store — no Android `Context` required. Keys are non-PII and
 * clear on uninstall; logout/`reset()` never touches them.
 */
class GrowthInstallState(
    private val store: GrowthInstallStateStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val firstOpenFired: Boolean get() = store.getBoolean(KEY_FIRST_OPEN, false)
    val installAt: Long? get() = store.getLong(KEY_INSTALL_AT, 0L).takeIf { it > 0L }

    /** Read persisted state, classify this launch, and persist the new state. [signal] is
     *  only consulted on the first SDK run; pass [GrowthInstallSignal.UNKNOWN] otherwise. */
    fun resolveLaunch(
        currentVersion: String?,
        currentBuild: String?,
        signal: GrowthInstallSignal,
        isProduction: Boolean,
        trackBuildChanges: Boolean,
    ): GrowthLaunchType {
        val alreadyFired = store.getBoolean(KEY_FIRST_OPEN, false)
        val lastVersion = store.getString(KEY_LAST_VERSION)
        val lastBuild = store.getString(KEY_LAST_BUILD)

        if (alreadyFired) {
            // No recorded baseline: a pre-0.4.0 install that persisted `first_open_fired`
            // before version bookkeeping existed, or one marked pre-existing without a
            // version. With nothing to diff against, adopt this launch's version as the
            // baseline silently — never fabricate an
            // `app.updated(VERSION_CHANGE, fromVersion=null)` across the installed base.
            if (lastVersion == null && lastBuild == null) { persistBaseline(currentVersion, currentBuild); return GrowthLaunchType.Launch }
            // Only a *present* current value that differs is a real change. A null current
            // (PackageManager lookup failed, or nullable versionName) carries no information —
            // never fabricate an app.updated from missing data (and never wipe the baseline).
            val versionChanged = currentVersion != null && lastVersion != currentVersion
            val buildChanged = currentBuild != null && lastBuild != currentBuild
            if (!versionChanged && !buildChanged) { persistBaseline(currentVersion, currentBuild); return GrowthLaunchType.Launch }
            val reason = if (versionChanged) GrowthUpdateReason.VERSION_CHANGE else GrowthUpdateReason.BUILD_CHANGE
            // Build numbers churn on every CI build; outside production a build-only
            // bump is noise unless explicitly opted in.
            if (reason == GrowthUpdateReason.BUILD_CHANGE && !isProduction && !trackBuildChanges) {
                persistBaseline(currentVersion, currentBuild); return GrowthLaunchType.Launch
            }
            // REAL update: do NOT advance the baseline here. The caller persists it via
            // [persistBaseline] only AFTER the app.updated event posts, so a transient send
            // failure leaves the old baseline in place and the next launch retries
            // (at-least-once) instead of silently dropping the update.
            return GrowthLaunchType.Update(reason, lastVersion, lastBuild)
        }

        store.putBoolean(KEY_FIRST_OPEN, true)
        if (signal == GrowthInstallSignal.EXISTED) {
            // Pre-existing install adopting the SDK: never count as install. Mark first-open +
            // adopt the baseline BEFORE sending (at-most-once): if this one adoption event
            // fails to send it is lost, which is the safe tradeoff — retrying would re-run the
            // probe and a flaky probe could then misclassify a pre-existing user as a new install.
            persistBaseline(currentVersion, currentBuild)
            return GrowthLaunchType.Update(GrowthUpdateReason.PRE_EXISTING_INSTALL, null, null)
        }
        // FRESH or UNKNOWN → genuine fresh install (never under-count). At-most-once: baseline
        // persisted before send so a failed install is never re-counted.
        store.putLong(KEY_INSTALL_AT, clock())
        persistBaseline(currentVersion, currentBuild)
        return GrowthLaunchType.FreshInstall
    }

    /** Advance the stored version/build baseline. Called by [resolveLaunch] for non-real-update
     *  outcomes, and by the lifecycle caller AFTER a real `app.updated` posts (at-least-once).
     *  Only writes present values, so a null current never erases a good baseline. */
    fun persistBaseline(currentVersion: String?, currentBuild: String?) {
        if (currentVersion != null) store.putString(KEY_LAST_VERSION, currentVersion)
        if (currentBuild != null) store.putString(KEY_LAST_BUILD, currentBuild)
    }

    /** Mark this install as pre-existing without firing `app.first_open`. Idempotent. */
    fun markInstalledBeforeTracking(currentVersion: String?, currentBuild: String?) {
        if (store.getBoolean(KEY_FIRST_OPEN, false)) return
        store.putBoolean(KEY_FIRST_OPEN, true)
        store.putString(KEY_LAST_VERSION, currentVersion)
        store.putString(KEY_LAST_BUILD, currentBuild)
    }

    companion object {
        // Key names match the pre-0.4.0 lifecycle prefs so existing installs keep their
        // first-open flag and never re-fire after upgrading the SDK.
        const val KEY_FIRST_OPEN = "first_open_fired"
        const val KEY_INSTALL_AT = "install_at_ms"
        const val KEY_LAST_VERSION = "last_app_version"
        const val KEY_LAST_BUILD = "last_build_number"

        /** App updated at least once ⇒ existing user. Equal times ⇒ never updated ⇒ fresh. */
        const val INSTALL_UPDATE_EPSILON_MS = 1_000L

        fun signalFromInstallTimes(firstInstallTime: Long, lastUpdateTime: Long): GrowthInstallSignal =
            if (lastUpdateTime - firstInstallTime > INSTALL_UPDATE_EPSILON_MS) {
                GrowthInstallSignal.EXISTED
            } else {
                GrowthInstallSignal.FRESH
            }
    }
}

/** [GrowthInstallStateStore] backed by Android `SharedPreferences`. */
internal class SharedPrefsInstallStateStore(private val prefs: SharedPreferences) : GrowthInstallStateStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}

/**
 * Reads `PackageManager` first-install / last-update times. Returns
 * [GrowthInstallSignal.EXISTED] when the app has been updated at least once (an existing
 * user), [GrowthInstallSignal.FRESH] for a never-updated install, and
 * [GrowthInstallSignal.UNKNOWN] on any error (so we fall back to firing `app.first_open`).
 *
 * Note: clearing app data wipes our prefs but not the OS install times, so a clear-data on
 * an already-updated install reports EXISTED and is treated as a known install rather than
 * a re-acquisition. This is the deliberate trade-off — see the design doc. For an exact
 * adoption mitigation, call [GrowthLifecycleObserver.markInstalledBeforeTracking].
 */
class AndroidInstallSignalProvider(private val context: Context) : InstallSignalProvider {
    override fun priorInstallSignal(): GrowthInstallSignal = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        GrowthInstallState.signalFromInstallTimes(info.firstInstallTime, info.lastUpdateTime)
    }.getOrDefault(GrowthInstallSignal.UNKNOWN)
}

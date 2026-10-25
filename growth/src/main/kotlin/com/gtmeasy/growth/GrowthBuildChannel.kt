package com.gtmeasy.growth

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Where the running build came from. Used by
 * [GrowthAnalyticsConfiguration.suppressTestBuilds] to drop events from debug and
 * test builds. [UNKNOWN] is never suppressed (fail open: production data must not be
 * lost because detection failed).
 */
enum class GrowthBuildChannel(val wire: String) {
    /** Debuggable build (Android Studio run, `assembleDebug`). */
    DEBUG("debug"),
    /** Apple TestFlight. Never auto-detected on Android; settable via override for parity. */
    TESTFLIGHT("testflight"),
    /** Internal / pre-release test distribution (Firebase App Distribution, test flavors). */
    INTERNAL_TEST("internal_test"),
    /** Installed from Google Play. Play internal/closed testing is indistinguishable on-device. */
    PRODUCTION("production"),
    /** No evidence (JVM, sideload, other stores, lookup failure). Never suppressed. */
    UNKNOWN("unknown");

    /** True for channels that [GrowthAnalyticsConfiguration.suppressTestBuilds] drops. */
    val isTestChannel: Boolean get() = this == DEBUG || this == TESTFLIGHT || this == INTERNAL_TEST
}

/**
 * Offline evidence used to classify the build channel. Inject a fake into
 * [GrowthAnalytics] in tests; production uses the `PackageManager`-backed probe.
 */
interface GrowthBuildChannelProbe {
    /** `ApplicationInfo.FLAG_DEBUGGABLE` of the host app; null when unknown. */
    fun isDebuggable(): Boolean?

    /** Package name of the installer (e.g. `com.android.vending`); null when unknown/sideloaded. */
    fun installerPackageName(): String?
}

/** `PackageManager`-backed probe. Pure local reads — no network, no IPC to Play services. */
internal class AndroidBuildChannelProbe(private val context: Context) : GrowthBuildChannelProbe {
    override fun isDebuggable(): Boolean? = runCatching {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrNull()

    override fun installerPackageName(): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
}

/** Offline, synchronous, cached build-channel detection. */
object GrowthBuildChannelDetector {
    const val INSTALLER_PLAY_STORE = "com.android.vending"
    const val INSTALLER_FIREBASE_APP_DISTRIBUTION = "dev.firebase.appdistribution"

    @Volatile private var cached: GrowthBuildChannel? = null

    /** Classify from [probe]. Uncached; any probe failure degrades toward [GrowthBuildChannel.UNKNOWN]. */
    fun detect(probe: GrowthBuildChannelProbe): GrowthBuildChannel {
        if (runCatching { probe.isDebuggable() }.getOrNull() == true) return GrowthBuildChannel.DEBUG
        return when (runCatching { probe.installerPackageName() }.getOrNull()) {
            INSTALLER_FIREBASE_APP_DISTRIBUTION -> GrowthBuildChannel.INTERNAL_TEST
            INSTALLER_PLAY_STORE -> GrowthBuildChannel.PRODUCTION
            else -> GrowthBuildChannel.UNKNOWN
        }
    }

    /** Detect for the host app once per process; JVM / no [context] → [GrowthBuildChannel.UNKNOWN]. */
    fun detect(context: Context?): GrowthBuildChannel {
        if (context == null) return GrowthBuildChannel.UNKNOWN
        cached?.let { return it }
        return synchronized(this) {
            cached ?: detect(AndroidBuildChannelProbe(context)).also { cached = it }
        }
    }

    /** The effective suppression rule, identical in every GTM Easy SDK. */
    fun isSuppressed(disabled: Boolean, suppressTestBuilds: Boolean, channel: () -> GrowthBuildChannel): Boolean =
        disabled || (suppressTestBuilds && channel().isTestChannel)

    internal fun resetCacheForTests() {
        cached = null
    }
}

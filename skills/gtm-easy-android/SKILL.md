---
name: gtm-easy-android
description: Integrate the GTM Easy growth analytics SDK (`com.gtmeasy:growth`) into an Android / JVM Kotlin app. Use when (1) The user wants to install, wire up, or upgrade `com.gtmeasy:growth` for Android, (2) The user mentions "GTM Easy", "growth analytics", "gtmeasy.com", "GrowthAnalytics", or "gtm-easy-android-sdk", (3) The user wants to ship paywall funnel events, identify users, capture ad-platform click IDs (gclid/fbclid/...), or wire Google Play Install Referrer attribution from a Kotlin codebase, (4) The user wants GAID + Limit-Ad-Tracking handled correctly, lifecycle observer set up, or Play subscription webhooks wired in.
---

# GTM Easy Android / JVM integration

Wire `com.gtmeasy:growth` (Android API 23+ or JVM 11+) into the host app. Covers Gradle dependency, lifecycle setup, identify + track, click-id capture, GAID/LAT, paywall events, and Play Install Referrer attribution.

## Repo layout reference

Canonical SDK source: <https://github.com/gtmeasy/gtm-easy-android-sdk>.
Working sample (every public surface, 5-tab Compose app): `examples/TwilarSample/` in that repo. When unsure about a public API, read the corresponding file under `examples/TwilarSample/app/src/main/kotlin/com/gtmeasy/twilarsample/`.

## 1. Add the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.gtmeasy:growth:0.2.0")

    // Optional — only if the host app already ships these:
    // implementation("com.android.installreferrer:installreferrer:2.2") // for Play Install Referrer
}
```

The SDK is **published to Maven Central**; no extra repository declarations are needed.

## 2. Singleton wrapper (always do this)

`GrowthAnalytics` owns persistent state (mutex, anonymous-id cache, click-id store). Host apps must construct it exactly once per process. Drop this in `app/src/main/kotlin/<pkg>/growth/GrowthClient.kt`:

```kotlin
package com.example.growth

import android.content.Context
import android.content.pm.ApplicationInfo
import com.gtmeasy.growth.GrowthAnalytics
import com.gtmeasy.growth.GrowthAnalyticsConfiguration

/// Helper to determine the build and distribution environment
object EnvHelper {
    /**
     * True if the app is running in a debuggable build (local/emulator runs).
     */
    fun isDebug(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * True if the app was NOT installed from the Google Play Store (e.g. sideloaded, Firebase App Distribution, or local build).
     */
    fun isSandboxOrLocal(context: Context): Boolean {
        val pm = context.packageManager
        val installer = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
        // Installer is null for sideloads/local debug runs, and different for alternate app stores
        return installer != "com.android.vending"
    }

    /**
     * True if running in a debug build or installed via non-Play Store channels (sandbox/local)
     */
    fun isDebugOrSandbox(context: Context): Boolean {
        return isDebug(context) || isSandboxOrLocal(context)
    }
}

object GrowthClient {
    @Volatile private var instance: GrowthAnalytics? = null

    fun init(applicationContext: Context): GrowthAnalytics =
        instance ?: synchronized(this) {
            instance ?: GrowthAnalytics(
                GrowthAnalyticsConfiguration(
                    app = "<gtm-easy-app-id>",         // from gtmeasy.com → Settings
                    writeKey = "<per-app-write-key>",  // public SDK key, safe to ship
                    context = applicationContext,
                    environment = GrowthAnalyticsConfiguration.Environment.PRODUCTION,
                    // Disable the SDK in debug runs and non-production testing channels by default
                    disabled = EnvHelper.isDebugOrSandbox(applicationContext),
                )
            ).also { instance = it }
        }

    fun require(): GrowthAnalytics =
        instance ?: error("GrowthClient.init() must run from Application.onCreate")
}
```

`endpoint` defaults to `GrowthAnalyticsConfiguration.DEFAULT_ENDPOINT` (`https://www.gtmeasy.com`). Override only for self-hosted or LAN dev.

## 3. Wire `Application.onCreate` + lifecycle observer

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val analytics = GrowthClient.init(this)
        val observer = GrowthLifecycleObserver(analytics, this)
        // First adoption only: if this app already had users before adding the SDK, mark
        // known-existing users so they aren't counted as new installs. Call BEFORE register().
        // if (userExistedBeforeThisRelease) observer.markInstalledBeforeTracking()
        // Fires app.first_open (fresh install only) / app.updated (version change) +
        // app.opened on every foreground.
        observer.register()
    }
}
```

Register the `Application` subclass in `AndroidManifest.xml` via `android:name=".MyApp"`.

NEVER call `analytics.trackFirstOpen()` manually after registering the lifecycle observer — it bypasses the `SharedPreferences` install/update guard and will count app updates as new installs.

## 4. Deep-link click-id capture

`gclid` / `fbclid` / `ttclid` / etc. arrive via deep links. Capture in `MainActivity.onCreate` AND `onNewIntent`:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { GrowthClient.require().clickIdStore.captureClickIds(it.toString()) }
        // ...
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { GrowthClient.require().clickIdStore.captureClickIds(it.toString()) }
    }
}
```

The store auto-synthesizes Meta `_fbc` / `_fbp` and persists each click ID for 90 days.

## 5. Identify + track

```kotlin
lifecycleScope.launch {
    // username + email are first-class — pass by name. They sit after `traits`, so the
    // legacy positional call identify("user_123", mapOf(...)) keeps working unchanged.
    analytics.identify(userId = "user_123", username = "john_wayne", email = "u@x.com", traits = mapOf("plan" to "pro"))
    analytics.track("feature.used", mapOf("feature" to "export"))

    // On logout: forget the identity and rotate the anonymous id.
    analytics.reset()
}
```

`username` + `email` persist in `SharedPreferences` and reattach to every later `track`.

Email/phone in traits are SHA-256 hashed server-side for Enhanced Matching — never hash on the client.

## 6. Paywall funnel — use the typed helpers

Ad-platform connectors (Meta CAPI, Google Ads, TikTok Events) depend on canonical payload shapes. Hand-rolled `track("paywall.…")` payloads will drift. Use:

```kotlin
analytics.trackPaywallOpened(placement = "settings_upgrade", productIds = listOf("pro_yearly"))
analytics.trackPaywallPlanSelected(placement = "settings_upgrade", productId = "pro_yearly", price = 49.99, currency = "USD")
analytics.trackPaywallUpgradeClicked(placement = "settings_upgrade", productId = "pro_yearly", price = 49.99, currency = "USD")
analytics.trackPurchaseCompleted(amount = 49.99, currency = "USD", productId = "pro_yearly")
```

Also available: `trackPaywallUpgradeCancelled`, `trackPaywallClosed`, `trackTrialStarted`, `trackRestoreCompleted`.

## 7. Google Play Install Referrer attribution

```kotlin
analytics.collectPlayInstallReferrer()
```

Requires `com.android.installreferrer:installreferrer:2.2` on the classpath (declared `compileOnly` in the SDK so you opt in). The raw referrer string is posted to `/api/v1/growth/attribution/play-install-referrer` where UTM + campaign metadata are resolved.

## 8. Google Play subscription notifications

Configure your Play Console RTDN topic to forward to `https://<your-gtm-easy-host>/api/v1/growth/webhooks/playstore`. The server verifies the Pub/Sub OIDC JWT and emits `subscription.renewed` / `subscription.expired` events keyed to the same identity as in-app events. No client-side wiring required.

## 9. Bridges — one user, all your tools

GTM Easy never bundles Clarity / PostHog / Sentry / Statsig. Install a bridge so all of them resolve to the same user:

```kotlin
analytics.bridges += ClarityBridge { id -> Clarity.setCustomUserId(id) }
analytics.bridges += PostHogBridge { id, traits -> PostHog.identify(id, traits.toUserProperties(), null) }
analytics.bridges += SentryBridge  { id -> Sentry.setUser(io.sentry.protocol.User().apply { setId(id) }) }
analytics.bridges += StatsigBridge { id -> Statsig.updateUserAsync(StatsigUser(userID = id)) }
```

Bridge failures never bubble up — bridge code is wrapped in `runCatching`.

## 10. Onboarding surveys

Capture flexible onboarding answers — choice breakdowns, rating histograms, NPS, and free-text samples aggregate on the dashboard with no server-side survey definition. Mark the survey shown first (optional, drives the shown→completed rate), then submit answers built with the `SurveyAnswers` factories:

```kotlin
import com.gtmeasy.growth.SurveyAnswers
import com.gtmeasy.growth.trackSurveyShown

lifecycleScope.launch {
    analytics.trackSurveyShown(surveyId = "onboarding_v1", surveyName = "Onboarding")

    val ack = analytics.submitSurvey(
        surveyId = "onboarding_v1",
        surveyName = "Onboarding",
        surveyVersion = "2",
        responses = listOf(
            SurveyAnswers.singleChoice("source", "tiktok", label = "TikTok", questionText = "Where did you hear about us?"),
            SurveyAnswers.multiChoice("goals", listOf("focus", "limits"), labels = listOf("Stay focused", "Set limits")),
            SurveyAnswers.nps("recommend", 9),
            SurveyAnswers.rating("first_impression", 5),
            SurveyAnswers.text("anything_else", "Loving it so far"),
        ),
    )
}
```

Pass `status = SurveyStatus.PARTIAL` to store answers without firing a completion event, or `SurveyStatus.DISMISSED` when the user closes it. The SDK mints the `submissionId` on-device so a transparent retry reuses the same key (server dedups); pass your own to make app-level retries idempotent. Don't truncate free text — the survey store accepts up to 2 000 chars per answer.

Attach free-form `metadata: Map<String, Any?>` to the whole submission (`submitSurvey(..., metadata = mapOf("variant" to "B"))`, echoed onto every answer row) or to a single answer (`SurveyAnswers.rating("q", 5, metadata = mapOf("ms_to_answer" to 1200))`, merged **over** the submission-level payload). It lands in a JSON column read with `JSONExtract` on demand — use it for A/B variants, answer timings, or any field you may add later, with no schema migration.

## 11. Things to NOT do

- **Don't construct `GrowthAnalytics` per call site.** Use the singleton in `GrowthClient`.
- **Don't fire `trackFirstOpen()` manually after registering `GrowthLifecycleObserver`.** It'll double-fire.
- **Don't pass GAID manually.** `GrowthDeviceIdentifiers` reads it reflectively and suppresses GAID when Limit-Ad-Tracking is on, per Google policy.
- **Don't hash email/phone before passing to `identify`.** The server hashes; double-hashing breaks Enhanced Matching.
- **Don't depend on `usesCleartextTraffic`** unless you're pointing at an HTTP LAN dev server. Production is HTTPS by default.
- **Don't add if (!BuildConfig.DEBUG) guards around analytics calls.** Set `disabled` dynamically using `EnvHelper.isDebugOrSandbox(context)` in the configuration wrapper instead — all methods still return valid `IngestResponse` objects and call sites stay clean.

## 12. Verifying the wire-up

1. Install the debug APK on an emulator with Google Play services.
2. First cold start on a fresh install produces `app.first_open` + `app.opened`. Subsequent same-version launches produce only `app.opened`. After an app update, the first launch produces `app.updated` + `app.opened` (never a second `app.first_open`) — visible in the GTM Easy dashboard → Events.
3. Send the app a deep-link click ID:
   ```bash
   adb shell am start -W -a android.intent.action.VIEW \
     -d "yourapp://onboarding?gclid=adb_demo&utm_campaign=test" \
     <package_name>
   ```
   The next event's `_ctx.gclid` must be `adb_demo`.
4. Call `analytics.identify("user_123")` — verify the dashboard Users view shows `user_123` bound to the device's anonymous id.

If nothing arrives, flip `environment = STAGING` + `debug = true` to surface failures (otherwise 401/403 from a wrong write key are silent in production).

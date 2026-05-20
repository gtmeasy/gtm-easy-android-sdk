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
import com.gtmeasy.growth.GrowthAnalytics
import com.gtmeasy.growth.GrowthAnalyticsConfiguration

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
        // Fires app.first_open (once per install) + app.opened on every foreground.
        GrowthLifecycleObserver(analytics, this).register()
    }
}
```

Register the `Application` subclass in `AndroidManifest.xml` via `android:name=".MyApp"`.

NEVER call `analytics.trackFirstOpen()` manually after registering the lifecycle observer — it'll double-fire. The observer's `app.first_open` is UserDefaults-guarded (well, `SharedPreferences`-guarded); manual calls are not.

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
    analytics.identify(userId = "user_123", traits = mapOf("plan" to "pro", "email" to "u@x.com"))
    analytics.track("feature.used", mapOf("feature" to "export"))
}
```

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

## 10. Things to NOT do

- **Don't construct `GrowthAnalytics` per call site.** Use the singleton in `GrowthClient`.
- **Don't fire `trackFirstOpen()` manually after registering `GrowthLifecycleObserver`.** It'll double-fire.
- **Don't pass GAID manually.** `GrowthDeviceIdentifiers` reads it reflectively and suppresses GAID when Limit-Ad-Tracking is on, per Google policy.
- **Don't hash email/phone before passing to `identify`.** The server hashes; double-hashing breaks Enhanced Matching.
- **Don't depend on `usesCleartextTraffic`** unless you're pointing at an HTTP LAN dev server. Production is HTTPS by default.

## 11. Verifying the wire-up

1. Install the debug APK on an emulator with Google Play services.
2. First cold start should produce `app.first_open` + `app.opened`. Subsequent launches produce only `app.opened` (visible in the GTM Easy dashboard → Events).
3. Send the app a deep-link click ID:
   ```bash
   adb shell am start -W -a android.intent.action.VIEW \
     -d "yourapp://onboarding?gclid=adb_demo&utm_campaign=test" \
     <package_name>
   ```
   The next event's `_ctx.gclid` must be `adb_demo`.
4. Call `analytics.identify("user_123")` — verify the dashboard Users view shows `user_123` bound to the device's anonymous id.

If nothing arrives, flip `environment = STAGING` + `debug = true` to surface failures (otherwise 401/403 from a wrong write key are silent in production).

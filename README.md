# GTM Easy Android SDK

First-party Kotlin SDK for [GTM Easy](https://gtmeasy.com) growth analytics, native attribution, and ad-platform conversion APIs. Sends events to the GTM Easy ingestion API, identifies users, persists an anonymous ID, captures GAID + click IDs, drives the paywall funnel, collects Google Play Install Referrer attribution, and bridges a single user across the other analytics tools you already use (Microsoft Clarity, PostHog, Sentry incl. self-hosted, Statsig).

Targets: **Android 6.0 (API 23)+** and **JVM 11+** for backend / server use.

```kotlin
implementation("com.gtmeasy:growth:0.2.0")
```

## What's new (v0.2.0)

- **Lifecycle observer**: `GrowthLifecycleObserver(analytics, context).register()` fires `app.first_open` once + `app.opened` on every foreground.
- **Device identifiers**: `GrowthDeviceIdentifiers(context).snapshot()` reads GAID via reflection (no hard dep on `play-services-ads-identifier`) with a 1s timeout.
- **Click ID store**: `GrowthClickIdStore(context)` persists every ad-platform click ID with 90-day TTL, synthesizes Meta `_fbc`/`_fbp`, accepts `captureClickIds(uri)` for deep-link parsing.
- **Typed paywall events**: extension functions like `analytics.trackPaywallOpened(placement, variant, productIds)`.
- **Debug mirror**: `GrowthAnalyticsConfiguration(debug = true)` emits to `GrowthDebugSink.events` SharedFlow + logcat.

## Quick start

```kotlin
import com.gtmeasy.growth.GrowthAnalytics
import com.gtmeasy.growth.GrowthAnalyticsConfiguration

val analytics = GrowthAnalytics(
    GrowthAnalyticsConfiguration(
        app = "<gtm-easy-app-id>",
        writeKey = "<per-app-write-key>",
        context = applicationContext,
    )
)

// `endpoint` defaults to `GrowthAnalyticsConfiguration.DEFAULT_ENDPOINT`
// (https://www.gtmeasy.com). Override only for self-hosted deployments or
// local development:
//
// GrowthAnalyticsConfiguration(
//     app = "<gtm-easy-app-id>",
//     writeKey = "<per-app-write-key>",
//     endpoint = "https://your-self-hosted.example.com",
//     environment = GrowthAnalyticsConfiguration.Environment.DEVELOPMENT,
// )

lifecycleScope.launch {
    analytics.identify(userId = "user_123", traits = mapOf("plan" to "pro"))
    analytics.trackFirstOpen()
    analytics.trackPurchaseCompleted(amount = 9.99, currency = "USD", productId = "pro_monthly")
}
```

## Identifying users

`identify` attaches a stable **userId** plus optional **username** and **email** to
the current anonymous stream. All three are first-class (not smuggled in `traits`),
persisted to `SharedPreferences`, and reused automatically on later `track` calls — so
a purchase after a process restart still attributes to the signed-in user. On the
server these power the People dashboard and feed hashed ad-platform match keys (email
is hashed only at ad-platform egress; plaintext at rest).

```kotlin
lifecycleScope.launch {
    analytics.identify(
        userId = "user_123",
        username = "john_wayne",
        email = "john@example.com",
        traits = mapOf("plan" to "pro"),
    )
}
```

`username` and `email` sit after `traits`, so the legacy positional call
`identify("user_123", mapOf(...))` keeps working unchanged — pass the new fields by
name. On logout, call `reset()` to forget the identity and rotate the anonymous id so
later events start a fresh anonymous stream instead of re-stitching onto the previous
user:

```kotlin
lifecycleScope.launch { analytics.reset() }
```

## Bridges — one user, all your tools

GTM Easy does not bundle Clarity / PostHog / Sentry / Statsig — you keep your existing SDKs. Install a bridge to make sure the **same user** shows up in each of them.

```kotlin
import com.microsoft.clarity.Clarity
import com.posthog.PostHog
import io.sentry.Sentry
import com.statsig.androidsdk.Statsig

analytics.bridges += ClarityBridge { id -> Clarity.setCustomUserId(id) }
analytics.bridges += PostHogBridge { id, traits ->
    PostHog.identify(id, traits.toUserProperties(), null)
}
analytics.bridges += SentryBridge { id ->
    Sentry.setUser(io.sentry.protocol.User().apply { setId(id) })
}
analytics.bridges += StatsigBridge { id ->
    Statsig.updateUserAsync(com.statsig.androidsdk.StatsigUser(userID = id))
}
```

Bridges are pure interfaces — we never pull the host SDKs as a dependency.

## Google Play Install Referrer attribution

```kotlin
analytics.collectPlayInstallReferrer()
```

The SDK uses Google's Play Install Referrer Library when present, and sends the raw referrer string to `POST /api/v1/growth/attribution/play-install-referrer`. The GTM Easy server resolves UTM and campaign metadata.

## Development

```bash
./gradlew test
```

## License

MIT

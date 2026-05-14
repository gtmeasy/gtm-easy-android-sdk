# GTM Easy Android SDK

First-party Kotlin SDK for [GTM Easy](https://gtmeasy.com) growth analytics. Sends events to the GTM Easy ingestion API, identifies users, persists an anonymous ID, collects Google Play Install Referrer attribution, and bridges a single user across the other analytics tools you already use (Microsoft Clarity, PostHog, Sentry incl. self-hosted, Statsig).

Targets: **Android 6.0 (API 23)+** and **JVM 11+** for backend / server use.

```kotlin
implementation("com.gtmeasy:growth:0.1.0")
```

## Quick start

```kotlin
import com.gtmeasy.growth.GrowthAnalytics
import com.gtmeasy.growth.GrowthAnalyticsConfiguration

val analytics = GrowthAnalytics(
    GrowthAnalyticsConfiguration(
        app = "<gtm-easy-app-id>",
        endpoint = "https://www.gtmeasy.com",
        writeKey = "<per-app-write-key>",
        environment = GrowthAnalyticsConfiguration.Environment.PRODUCTION,
        context = applicationContext,
    )
)

lifecycleScope.launch {
    analytics.identify(userId = "user_123", traits = mapOf("plan" to "pro"))
    analytics.trackFirstOpen()
    analytics.trackPurchaseCompleted(amount = 9.99, currency = "USD", productId = "pro_monthly")
}
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

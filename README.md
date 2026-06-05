# GTM Easy Android SDK

First-party Kotlin SDK for [GTM Easy](https://gtmeasy.com) growth analytics, native attribution, and ad-platform conversion APIs. Sends events to the GTM Easy ingestion API, identifies users, persists an anonymous ID, captures GAID + click IDs, drives the paywall funnel, collects Google Play Install Referrer attribution, and bridges a single user across the other analytics tools you already use (Microsoft Clarity, PostHog, Sentry incl. self-hosted, Statsig).

Targets: **Android 6.0 (API 23)+** and **JVM 11+** for backend / server use.

```kotlin
implementation("com.gtmeasy:growth:0.6.0")
```

## What's new (v0.6.0)

- **App updates are no longer counted as new installs.** `GrowthLifecycleObserver` now fires
  `app.first_open` only for a genuine fresh install. When the app's `versionName`/`versionCode`
  changes between launches it fires `app.updated` instead — a non-install lifecycle event the
  server never counts as an acquisition, never alerts on, and never forwards to an ad platform.
- **Adoption-spike protection.** Existing-user installs are detected automatically from
  `PackageManager` first-install/last-update times. For an exact mitigation in the release
  that first adds the SDK, call `observer.markInstalledBeforeTracking()` for users you already
  know are existing (signed-in, has local data).
- **Ordered lifecycle.** `app.first_open`/`app.updated` is emitted before the first `app.opened`.

## What's new (v0.5.0)

- **Version alignment**: the GTM Easy SDKs (Kotlin / TypeScript / Swift) are now unified at **0.5.0** — no API changes since 0.4.x, so onboarding surveys + extensible survey metadata ship at the same version on every platform.

## What's new (v0.4.0)

- **Onboarding surveys**: `analytics.submitSurvey(surveyId, responses)` captures flexible, self-describing survey answers (single/multi choice, rating, NPS, scale, boolean, free text) with no length truncation. Build answers with the `SurveyAnswers` factories. `trackSurveyShown` / `trackSurveyStarted` extension functions power the shown→completed funnel on the dashboard.

## What's new (v0.3.0)

- **First-class identity**: `identify(userId, traits, username, email)` accepts optional `username` and `email` as top-level fields (backed by `GrowthIdentityStore`), persisted to `SharedPreferences` and reused on every later `track`.
- **Logout-safe reset**: `identify`/`track`/`submitPlayInstallReferrer` snapshot identity under the mutex via `IdentitySnapshot`; `reset()` rotates the anon id and clears identity under one mutex section, so a concurrent logout can no longer tear the anon id.
- **Identity-aware bridges**: Clarity / PostHog / Sentry / Statsig propagate `username`/`email` and clear on logout.

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
    analytics.trackPurchaseCompleted(amount = 9.99, currency = "USD", productId = "pro_monthly")
}
```

> **Lifecycle events:** register `GrowthLifecycleObserver(analytics, context).register()`
> once in `Application.onCreate` (see [Install vs. update tracking](#install-vs-update-tracking))
> — it fires `app.first_open`, `app.updated`, and `app.opened` for you, gated by
> `SharedPreferences`. Do **not** call the raw `analytics.trackFirstOpen()` on every launch:
> it fires unconditionally and would count every app *update* as a brand-new install.

## Install vs. update tracking

`GrowthLifecycleObserver(analytics, context).register()` fires exactly one lifecycle
signal per launch — `app.first_open` only for a genuine fresh install, `app.updated` when
the version/build changes (never an install), and nothing extra on a same-version
relaunch — plus `app.opened` on every foreground. The first-open flag is persisted in
`SharedPreferences`, so an app update never re-fires an install.

### Suppressing the adoption spike

Adding the SDK to an app that already has users would otherwise report your installed base
as new installs. Existing users are detected automatically from `PackageManager`
install/update times; for an exact mitigation call `markInstalledBeforeTracking()` in the
adoption release, **before** `register()`:

```kotlin
val observer = GrowthLifecycleObserver(analytics, applicationContext)
if (userExistedBeforeThisRelease) observer.markInstalledBeforeTracking()
observer.register()
```

`app.updated` carries `{ from_version, from_build, to_version, to_build, reason, is_real_update }`.
Update adoption shows up in the dashboard's per-version breakdown; it is never an install.

`GrowthLifecycleObserver.register()` only fires from the **main process** — registering it in
a multi-process app's `Application.onCreate` is safe; secondary processes are ignored.

> **At-most-once:** the first-open flag is persisted *before* the event is sent and is never
> retried, so the SDK never double-counts an install. The trade-off is that a device that is
> offline on its very first launch (and never relaunches at the same version online) can miss
> `app.first_open`. Freshly downloaded apps are almost always online, so this is rare.

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

## Onboarding surveys

Capture flexible onboarding-survey answers. Each answer is self-describing (it
carries its question type + optional human label), so the GTM Easy dashboard
aggregates choice breakdowns, rating histograms, NPS, and free-text samples
without any server-side survey definition. Answers are stored verbatim (no
240-char truncation) in a dedicated survey store.

```kotlin
import com.gtmeasy.growth.SurveyAnswers
import com.gtmeasy.growth.trackSurveyShown

lifecycleScope.launch {
    // Optional: mark shown so the dashboard can compute a shown → completed rate.
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
    println("${ack.submissionId} ${ack.accepted}") // idempotency key + rows persisted
}
```

Pass `status = SurveyStatus.PARTIAL` to store answers without completing the
survey (no completion event fires), or `SurveyStatus.DISMISSED` when the user
closes it. Supply your own `submissionId` to make retries idempotent. A completed
or dismissed submission also records a `survey.completed` / `survey.dismissed`
lifecycle event for the user-journey timeline and connector fan-out.

### Extensible metadata

Attach free-form `metadata` to a submission (echoed onto every answer row) or to
an individual answer (merged **over** the submission-level payload). It lands in
a dedicated JSON column read with `JSONExtract` on demand — add A/B variants,
answer timings, or any future field **without a schema migration**. Both accept a
plain `Map<String, Any?>`.

```kotlin
analytics.submitSurvey(
    surveyId = "onboarding_v1",
    responses = listOf(
        SurveyAnswers.rating("first_impression", 5, metadata = mapOf("ms_to_answer" to 1200)),
        SurveyAnswers.text("anything_else", "Loving it"),
    ),
    metadata = mapOf("variant" to "B", "flow" to "paywall_first"), // on every row
)
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

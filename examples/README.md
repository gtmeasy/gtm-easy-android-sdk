# Twilar Sample — GTM Easy Growth Android SDK

End-to-end Jetpack Compose example that exercises every public surface of
`com.gtmeasy:growth`. Mirrors the iOS sample tab-for-tab so the same flows
can be tested on both platforms.

| Tab        | Surfaces                                                              |
|------------|-----------------------------------------------------------------------|
| Funnel     | `trackPaywallOpened`, `trackPaywallPlanSelected`, `trackPaywallUpgradeClicked`, `trackPaywallUpgradeCancelled`, `trackPaywallClosed`, `trackPurchaseCompleted`, `trackTrialStarted`, `trackRestoreCompleted` |
| Identity   | `getAnonymousId`, `setUserId`, `identify` with email/phone traits     |
| Click IDs  | `clickIdStore.captureClickIds(uri)` from deep links, `clickIdStore.record` |
| Lifecycle  | Auto-fires via `GrowthLifecycleObserver`; tab adds manual events      |
| Console    | Live tail of `GrowthDebugSink` (every identify + track)               |

## Architecture

```
examples/
└── TwilarSample/                    # standalone Android Studio project
    ├── settings.gradle.kts          # includeBuild("../..") — composite build of the SDK
    ├── build.gradle.kts             # root
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts         # depends on "com.gtmeasy:growth" (resolved via includeBuild)
        └── src/main/
            ├── AndroidManifest.xml  # twilar:// deep-link intent filter
            ├── kotlin/com/gtmeasy/twilarsample/
            │   ├── TwilarSampleApp.kt        # Application — inits SDK + lifecycle observer
            │   ├── MainActivity.kt           # captureClickIds from onCreate/onNewIntent
            │   ├── growth/GrowthClient.kt    # singleton wrapper
            │   └── ui/
            │       ├── TwilarSampleScreen.kt # tab shell
            │       ├── FunnelTab.kt          # paywall + purchase events
            │       ├── IdentityTab.kt        # identify / setUserId
            │       ├── ClickIdsTab.kt        # deep-link capture
            │       ├── LifecycleTab.kt       # manual lifecycle events
            │       └── ConsoleTab.kt         # GrowthDebugSink tail
            └── res/values/{strings,themes}.xml
```

The sample is a **composite Gradle build**: `settings.gradle.kts` does
`includeBuild("../../")` so any edit to the SDK lights up live in the sample.

## Run it

### Option A — Android Studio

```bash
studio packages/growth-kotlin-sdk/examples/TwilarSample
```

then ▶ Run against any API 23+ emulator.

### Option B — Gradle CLI

```bash
cd packages/growth-kotlin-sdk/examples/TwilarSample
./gradlew :app:installDebug
adb shell am start -n com.gtmeasy.twilarsample/.MainActivity
```

## Configuration

Edit `app/src/main/kotlin/com/gtmeasy/twilarsample/growth/GrowthClient.kt`:

```kotlin
const val APP = "twilar"
const val WRITE_KEY = "wk_sample_replace_me"             // gtmeasy.com → Settings → Write Keys
const val ENDPOINT = "http://192.168.3.241:3000"         // LAN staging
```

For production deployments use `https://www.gtmeasy.com` (the SDK default)
and drop `android:usesCleartextTraffic="true"` from the manifest.

> **Emulator → host networking:** Android emulators reach the host via
> `10.0.2.2`, not `localhost` and not the LAN IP. If you're pointing the
> sample at a dev server on your laptop, use `http://10.0.2.2:3000`.

## Testing deep-link click-id capture

```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d "twilar://onboarding?gclid=adb_demo&fbclid=adb_fb&utm_campaign=test" \
  com.gtmeasy.twilarsample
```

The next event will include `gclid`, `fbclid`, and the persisted `_fbc` in
`properties._ctx`. Watch the **Console** tab to confirm.

## Troubleshooting

| Symptom                              | Likely cause                                                                    |
|--------------------------------------|---------------------------------------------------------------------------------|
| Events show in Console, never hit backend | Wrong endpoint — emulator needs `10.0.2.2` or LAN IP, not `localhost`.     |
| `401` from `/events`                 | `WRITE_KEY` placeholder — replace with one from the dashboard.                  |
| `cleartext HTTP not permitted`       | Add `android:usesCleartextTraffic="true"` or use https.                         |
| GAID returns null                    | Emulator without Google Play services, or Limit Ad Tracking on. The SDK suppresses GAID when LAT is enabled per the privacy contract. |

package com.gtmeasy.growth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryInstallStore : GrowthInstallStateStore {
    private val bools = mutableMapOf<String, Boolean>()
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String?>()
    override fun getBoolean(key: String, default: Boolean) = bools[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { bools[key] = value }
    override fun getLong(key: String, default: Long) = longs[key] ?: default
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) { strings[key] = value }
}

private class CapturingHttpClient(
    private val response: GrowthHttpResponse = GrowthHttpResponse(201, "{\"event\":{\"id\":\"e\",\"eventName\":\"x\"}}"),
) : GrowthHttpClient {
    val bodies = mutableListOf<String>()
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GrowthHttpResponse {
        bodies += body
        return response
    }
}

private class StubAnon(private val id: String = "anon_1") : AnonymousIdStore {
    override fun get(): String = id
    override fun rotate(): String = id
}

class GrowthInstallStateTest {
    private val P = true   // isProduction
    private val NoBuild = false

    @Test fun `fresh signal fires fresh install`() {
        val store = InMemoryInstallStore()
        val state = GrowthInstallState(store) { 42L }
        val launch = state.resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        assertEquals(GrowthLaunchType.FreshInstall, launch)
        assertTrue(state.firstOpenFired)
        assertEquals(42L, state.installAt)
    }

    @Test fun `unknown signal biases to fresh install`() {
        val state = GrowthInstallState(InMemoryInstallStore())
        assertEquals(GrowthLaunchType.FreshInstall, state.resolveLaunch("2.3.0", "200", GrowthInstallSignal.UNKNOWN, P, NoBuild))
    }

    @Test fun `existed signal suppresses first_open`() {
        val store = InMemoryInstallStore()
        val state = GrowthInstallState(store)
        val launch = state.resolveLaunch("2.3.0", "200", GrowthInstallSignal.EXISTED, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.PRE_EXISTING_INSTALL, null, null), launch)
        assertTrue(state.firstOpenFired)
        assertNull(state.installAt)
    }

    @Test fun `relaunch same version is launch`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        val launch = GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Launch, launch)
    }

    @Test fun `first_open set with no baseline is launch not spurious update`() {
        // Pre-0.4.0 install: KEY_FIRST_OPEN exists (old lifecycle prefs) but the 0.4.0-new
        // version keys do not. The first version we observe must be adopted silently, NOT
        // reported as version_change with from=null across the whole installed base.
        val store = InMemoryInstallStore()
        store.putBoolean(GrowthInstallState.KEY_FIRST_OPEN, true)
        val launch = GrowthInstallState(store).resolveLaunch("2.0.0", "100", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Launch, launch)

        // The silent launch adopts 2.0.0 as the baseline, so a later real bump fires a
        // correctly-attributed update (proves the baseline was persisted in `finally`).
        val bump = GrowthInstallState(store).resolveLaunch("3.0.0", "200", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "2.0.0", "100"), bump)
    }

    @Test fun `version change fires version update`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        val launch = GrowthInstallState(store).resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "1.0.0", "10"), launch)
    }

    @Test fun `null current version against a baseline is launch and does not wipe the baseline`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        // Lookup failed this launch (null version/build): must NOT be a bogus update…
        val launch = GrowthInstallState(store).resolveLaunch(null, null, GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Launch, launch)
        // …and the baseline must survive so a later real bump is attributed correctly.
        val bump = GrowthInstallState(store).resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "1.0.0", "10"), bump)
    }

    @Test fun `real update defers baseline until caller persists`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        // A real update does NOT advance the baseline, so resolving again still reports it.
        val first = GrowthInstallState(store).resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "1.0.0", "10"), first)
        val retry = GrowthInstallState(store).resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "1.0.0", "10"), retry)
        // Once the caller persists the baseline (post-send), the next launch is silent.
        GrowthInstallState(store).persistBaseline("1.1.0", "11")
        val settled = GrowthInstallState(store).resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Launch, settled)
    }

    @Test fun `build-only change fires build update in production`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, P, NoBuild)
        val launch = GrowthInstallState(store).resolveLaunch("1.0.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.BUILD_CHANGE, "1.0.0", "10"), launch)
    }

    @Test fun `build-only change suppressed outside production`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, false, false)
        val launch = GrowthInstallState(store).resolveLaunch("1.0.0", "11", GrowthInstallSignal.UNKNOWN, false, false)
        assertEquals(GrowthLaunchType.Launch, launch)
    }

    @Test fun `build-only change outside production with opt-in`() {
        val store = InMemoryInstallStore()
        GrowthInstallState(store).resolveLaunch("1.0.0", "10", GrowthInstallSignal.FRESH, false, true)
        val launch = GrowthInstallState(store).resolveLaunch("1.0.0", "11", GrowthInstallSignal.UNKNOWN, false, true)
        assertEquals(GrowthLaunchType.Update(GrowthUpdateReason.BUILD_CHANGE, "1.0.0", "10"), launch)
    }

    @Test fun `reinstall with wiped store counts as fresh again`() {
        // Delete + reinstall wipes SharedPreferences → a clean store → fresh install.
        val state = GrowthInstallState(InMemoryInstallStore()) { 7L }
        assertEquals(GrowthLaunchType.FreshInstall, state.resolveLaunch("1.1.0", "11", GrowthInstallSignal.UNKNOWN, P, NoBuild))
        assertEquals(7L, state.installAt)
    }

    @Test fun `markInstalledBeforeTracking suppresses and is idempotent`() {
        val store = InMemoryInstallStore()
        val state = GrowthInstallState(store)
        state.markInstalledBeforeTracking("1.0.0", "10")
        assertTrue(state.firstOpenFired)
        assertNull(state.installAt)
        assertEquals(GrowthLaunchType.Launch, state.resolveLaunch("1.0.0", "10", GrowthInstallSignal.UNKNOWN, P, NoBuild))
        state.markInstalledBeforeTracking("9.9.9", "999") // no-op
        assertEquals("1.0.0", store.getString(GrowthInstallState.KEY_LAST_VERSION))
    }

    @Test fun `isMainProcessName gates secondary processes but fails open on unknown`() {
        // Default main process == package name → fires lifecycle.
        assertTrue(GrowthLifecycleObserver.isMainProcessName("com.app", "com.app"))
        // Unknown process name (resolution failed) → fail open, treat as main.
        assertTrue(GrowthLifecycleObserver.isMainProcessName(null, "com.app"))
        // Named processes must NOT fire — they'd double-fire app.first_open across processes.
        assertFalse(GrowthLifecycleObserver.isMainProcessName("com.app:remote", "com.app"))
        assertFalse(GrowthLifecycleObserver.isMainProcessName("com.app:push", "com.app"))
        // Custom main process via android:process on <application> → that process IS main.
        assertTrue(GrowthLifecycleObserver.isMainProcessName("com.app:main", "com.app:main"))
        // …and its other named children are still secondary.
        assertFalse(GrowthLifecycleObserver.isMainProcessName("com.app:remote", "com.app:main"))
    }

    @Test fun `signalFromInstallTimes`() {
        assertEquals(GrowthInstallSignal.FRESH, GrowthInstallState.signalFromInstallTimes(1000L, 1000L))
        assertEquals(GrowthInstallSignal.FRESH, GrowthInstallState.signalFromInstallTimes(1000L, 1500L)) // delta < epsilon
        assertEquals(GrowthInstallSignal.EXISTED, GrowthInstallState.signalFromInstallTimes(1000L, 5000L))
    }

    // --- fireLaunch mapping (classification → analytics event) ---

    private fun config() = GrowthAnalyticsConfiguration(
        app = "t", endpoint = "https://e.test", writeKey = "k",
        environment = GrowthAnalyticsConfiguration.Environment.STAGING,
    )

    @Test fun `fireLaunch FreshInstall sends app_first_open`() = runTest {
        val client = CapturingHttpClient()
        fireLaunch(GrowthAnalytics(config(), client, StubAnon()), GrowthLaunchType.FreshInstall, "1.0.0", "10")
        val body = Json.parseToJsonElement(client.bodies.single()).jsonObject
        assertEquals("app.first_open", (body["eventName"] as JsonPrimitive).content)
    }

    @Test fun `fireLaunch version update sends app_updated with props`() = runTest {
        val client = CapturingHttpClient()
        fireLaunch(GrowthAnalytics(config(), client, StubAnon()), GrowthLaunchType.Update(GrowthUpdateReason.VERSION_CHANGE, "0.9.0", "9"), "1.0.0", "10")
        val body = Json.parseToJsonElement(client.bodies.single()).jsonObject
        assertEquals("app.updated", (body["eventName"] as JsonPrimitive).content)
        val props = body["properties"] as JsonObject
        assertEquals("version_change", (props["reason"] as JsonPrimitive).content)
        assertEquals("0.9.0", (props["from_version"] as JsonPrimitive).content)
        assertEquals("1.0.0", (props["to_version"] as JsonPrimitive).content)
        assertEquals("true", (props["is_real_update"] as JsonPrimitive).content)
    }

    @Test fun `fireLaunch pre_existing marks is_real_update false`() = runTest {
        val client = CapturingHttpClient()
        fireLaunch(GrowthAnalytics(config(), client, StubAnon()), GrowthLaunchType.Update(GrowthUpdateReason.PRE_EXISTING_INSTALL, null, null), "1.0.0", "10")
        val body = Json.parseToJsonElement(client.bodies.single()).jsonObject
        val props = body["properties"] as JsonObject
        assertEquals("pre_existing_install", (props["reason"] as JsonPrimitive).content)
        assertEquals("false", (props["is_real_update"] as JsonPrimitive).content)
        assertNull(props["from_version"])
    }

    @Test fun `fireLaunch Launch sends nothing`() = runTest {
        val client = CapturingHttpClient()
        fireLaunch(GrowthAnalytics(config(), client, StubAnon()), GrowthLaunchType.Launch, "1.0.0", "10")
        assertTrue(client.bodies.isEmpty())
    }
}

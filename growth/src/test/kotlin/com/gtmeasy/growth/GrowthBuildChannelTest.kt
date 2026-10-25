package com.gtmeasy.growth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

private class FakeProbe(
    private val debuggable: Boolean? = false,
    private val installer: String? = null,
) : GrowthBuildChannelProbe {
    override fun isDebuggable(): Boolean? = debuggable
    override fun installerPackageName(): String? = installer
}

private class ThrowingProbe : GrowthBuildChannelProbe {
    override fun isDebuggable(): Boolean? = throw SecurityException("nope")
    override fun installerPackageName(): String? = throw IllegalArgumentException("nope")
}

private class CountingHttpClient : GrowthHttpClient {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GrowthHttpResponse {
        calls += url to body
        return GrowthHttpResponse(201, "{\"event\":{\"id\":\"e\",\"eventName\":\"x\"}}")
    }
}

private class TrackingAnonStore(private var id: String = "anon_existing") : AnonymousIdStore {
    var rotations = 0
    override fun get(): String = id
    override fun rotate(): String { rotations++; id = "anon_rotated"; return id }
}

private class MemInstallStore : GrowthInstallStateStore {
    val values = mutableMapOf<String, Any?>()
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String?) { values[key] = value }
}

class GrowthBuildChannelTest {
    private fun config(
        suppressTestBuilds: Boolean = true,
        buildChannel: GrowthBuildChannel? = null,
        disabled: Boolean = false,
        debug: Boolean = false,
    ) = GrowthAnalyticsConfiguration(
        app = "t", endpoint = "https://e.test", writeKey = "gte_secret",
        environment = GrowthAnalyticsConfiguration.Environment.PRODUCTION,
        debug = debug, disabled = disabled,
        suppressTestBuilds = suppressTestBuilds, buildChannel = buildChannel,
    )

    private fun analytics(
        config: GrowthAnalyticsConfiguration,
        http: GrowthHttpClient,
        probe: GrowthBuildChannelProbe = FakeProbe(),
        anon: AnonymousIdStore = TrackingAnonStore(),
        identity: IdentityStore = InMemoryIdentityStore(),
    ) = GrowthAnalytics(config, http, anon, identityStore = identity, buildChannelProbe = probe)

    // --- detection ---

    @Test fun `detection classifies injected probes`() {
        val d = GrowthBuildChannelDetector
        assertEquals(GrowthBuildChannel.DEBUG, d.detect(FakeProbe(debuggable = true)))
        // Debuggable wins even when installed from Play.
        assertEquals(GrowthBuildChannel.DEBUG, d.detect(FakeProbe(true, "com.android.vending")))
        assertEquals(GrowthBuildChannel.INTERNAL_TEST, d.detect(FakeProbe(false, "dev.firebase.appdistribution")))
        assertEquals(GrowthBuildChannel.PRODUCTION, d.detect(FakeProbe(false, "com.android.vending")))
        assertEquals(GrowthBuildChannel.UNKNOWN, d.detect(FakeProbe(false, null)))
        assertEquals(GrowthBuildChannel.UNKNOWN, d.detect(FakeProbe(false, "com.amazon.venezia")))
        assertEquals(GrowthBuildChannel.UNKNOWN, d.detect(FakeProbe(null, null)))
        assertEquals(GrowthBuildChannel.UNKNOWN, d.detect(ThrowingProbe()))
    }

    @Test fun `no context (JVM) resolves to unknown`() {
        assertEquals(GrowthBuildChannel.UNKNOWN, GrowthBuildChannelDetector.detect(null as Context?))
        val a = GrowthAnalytics(config(), CountingHttpClient(), TrackingAnonStore())
        assertEquals(GrowthBuildChannel.UNKNOWN, a.resolvedBuildChannel)
        assertFalse(a.isSuppressed)
    }

    @Test fun `suppression rule across channels`() {
        for (channel in GrowthBuildChannel.values()) {
            val expected = channel == GrowthBuildChannel.DEBUG ||
                channel == GrowthBuildChannel.TESTFLIGHT ||
                channel == GrowthBuildChannel.INTERNAL_TEST
            assertEquals(channel.name, expected, GrowthBuildChannelDetector.isSuppressed(false, true) { channel })
            assertFalse(channel.name, GrowthBuildChannelDetector.isSuppressed(false, false) { channel })
            assertTrue(channel.name, GrowthBuildChannelDetector.isSuppressed(true, false) { channel })
        }
    }

    @Test fun `override wins over detection`() {
        val prodOverride = analytics(config(buildChannel = GrowthBuildChannel.PRODUCTION), CountingHttpClient(), FakeProbe(debuggable = true))
        assertEquals(GrowthBuildChannel.PRODUCTION, prodOverride.resolvedBuildChannel)
        assertFalse(prodOverride.isSuppressed)

        val testOverride = analytics(config(buildChannel = GrowthBuildChannel.INTERNAL_TEST), CountingHttpClient(), FakeProbe(false, "com.android.vending"))
        assertEquals(GrowthBuildChannel.INTERNAL_TEST, testOverride.resolvedBuildChannel)
        assertTrue(testOverride.isSuppressed)
    }

    @Test fun `unknown is never suppressed`() = runTest {
        val http = CountingHttpClient()
        val a = analytics(config(), http, FakeProbe(false, null))
        assertEquals(GrowthBuildChannel.UNKNOWN, a.resolvedBuildChannel)
        assertFalse(a.isSuppressed)
        a.track("x")
        assertEquals(1, http.calls.size)
    }

    @Test fun `flag off never suppresses a debug build`() = runTest {
        val http = CountingHttpClient()
        val a = analytics(config(suppressTestBuilds = false), http, FakeProbe(debuggable = true))
        assertFalse(a.isSuppressed)
        a.track("x")
        assertEquals(1, http.calls.size)
    }

    // --- suppressed: zero network ---

    private suspend fun exerciseEveryPath(a: GrowthAnalytics) {
        a.identify("u1", mapOf("plan" to "pro"), username = "jw", email = "jw@example.com")
        a.track("feature.used", mapOf("k" to "v"))
        a.trackFirstOpen()
        a.trackAppOpen()
        a.trackAppUpdated("1.0", "1", "1.1", "2", GrowthUpdateReason.VERSION_CHANGE, true)
        a.trackPurchaseCompleted(9.99, "USD", "pro")
        a.trackPaywallOpened(placement = "onboarding")
        a.trackSurveyShown("s1")
        a.submitSurvey("s1", listOf(SurveyAnswers.rating("q", 5)))
        a.submitPlayInstallReferrer("utm_source=x")
        a.setUserId("u2")
        a.reset()
    }

    @Test fun `suppressed test build makes zero http calls on every path`() = runTest {
        val http = CountingHttpClient()
        val a = analytics(config(), http, FakeProbe(debuggable = true))
        assertTrue(a.isSuppressed)
        assertEquals(GrowthBuildChannel.DEBUG, a.resolvedBuildChannel)
        exerciseEveryPath(a)
        assertTrue(http.calls.isEmpty())
    }

    @Test fun `plain disabled makes zero http calls on every path`() = runTest {
        val http = CountingHttpClient()
        val a = analytics(config(suppressTestBuilds = false, disabled = true), http, FakeProbe(false, "com.android.vending"))
        assertTrue(a.isSuppressed)
        exerciseEveryPath(a)
        assertTrue(http.calls.isEmpty())
    }

    // --- suppressed: no durable writes ---

    @Test fun `suppressed identity is memory only and reads existing durable identity`() = runTest {
        val durable = InMemoryIdentityStore().apply { set(IdentityStore.KEY_EMAIL, "old@example.com") }
        val a = analytics(config(), CountingHttpClient(), FakeProbe(debuggable = true), identity = durable)
        assertEquals("old@example.com", a.getEmail())

        a.identify("u1", username = "jw", email = "new@example.com")
        a.setUserId("u3")
        assertEquals("u3", a.getUserId())
        assertEquals("new@example.com", a.getEmail())
        a.reset()
        assertNull(a.getEmail())

        // Durable store untouched.
        assertNull(durable.get(IdentityStore.KEY_USER_ID))
        assertNull(durable.get(IdentityStore.KEY_USERNAME))
        assertEquals("old@example.com", durable.get(IdentityStore.KEY_EMAIL))
    }

    @Test fun `suppressed anonymous id reuses existing id and rotates in memory only`() = runTest {
        val anon = TrackingAnonStore("anon_existing")
        val a = analytics(config(), CountingHttpClient(), FakeProbe(debuggable = true), anon = anon)
        assertEquals("anon_existing", a.getAnonymousId())
        a.reset()
        assertEquals(0, anon.rotations)
        assertTrue(a.getAnonymousId() != "anon_existing")
    }

    @Test fun `memory-only anonymous id generates without persisting when none exists`() {
        val store = MemoryOnlyAnonymousIdStore { null }
        val id = store.get()
        assertEquals(id, store.get())
        assertEquals(36, id.length)
    }

    @Test fun `memory-only click id store never opens shared preferences`() {
        val ctx = mock<Context>()
        val store = GrowthClickIdStore(ctx, memoryOnly = true)
        store.record(GrowthClickProvider.GCLID, "g1")
        assertEquals("g1", store.current(GrowthClickProvider.GCLID))
        verify(ctx, never()).getSharedPreferences(any(), any())
    }

    @Test fun `suppressed markInstalledBeforeTracking writes nothing`() {
        val prefs = mock<SharedPreferences>()
        val ctx = mock<Context> { on { getSharedPreferences(any(), any()) } doReturn prefs }
        val a = analytics(config(), CountingHttpClient(), FakeProbe(debuggable = true))
        GrowthLifecycleObserver(a, ctx, installSignalProvider = { GrowthInstallSignal.FRESH }).markInstalledBeforeTracking()
        verify(prefs, never()).edit()
    }

    @Test fun `suppressed launch then production relaunch fires app_first_open exactly once`() = runTest {
        val store = MemInstallStore()
        var signalAsked = 0
        val signal = InstallSignalProvider { signalAsked++; GrowthInstallSignal.FRESH }

        // 1. Test build (debuggable) on the device: nothing sent, nothing persisted.
        val testHttp = CountingHttpClient()
        val testBuild = analytics(config(), testHttp, FakeProbe(debuggable = true))
        classifyAndFireLaunch(testBuild, GrowthInstallState(store), signal, false, { "1.0.0-beta" }, { "9" })
        testBuild.trackAppOpen()
        assertTrue(testHttp.calls.isEmpty())
        assertTrue(store.values.isEmpty())
        assertEquals(0, signalAsked)

        // 2. Production build replaces it, same storage: first_open fires.
        val prodHttp = CountingHttpClient()
        val prod = analytics(config(), prodHttp, FakeProbe(false, "com.android.vending"))
        classifyAndFireLaunch(prod, GrowthInstallState(store), signal, false, { "1.0.0" }, { "10" })
        val body = Json.parseToJsonElement(prodHttp.calls.single().second).jsonObject
        assertEquals("app.first_open", (body["eventName"] as JsonPrimitive).content)

        // 3. Production relaunch: no second first_open.
        val relaunchHttp = CountingHttpClient()
        val relaunch = analytics(config(), relaunchHttp, FakeProbe(false, "com.android.vending"))
        classifyAndFireLaunch(relaunch, GrowthInstallState(store), signal, false, { "1.0.0" }, { "10" })
        assertTrue(relaunchHttp.calls.isEmpty())
    }

    // --- debug sink ---

    @Test fun `suppressed events mirror to debug sink with marker and no context`() = runTest {
        GrowthDebugSink.clear()
        val a = analytics(config(debug = true), CountingHttpClient(), FakeProbe(debuggable = true))
        a.track("paywall.opened", mapOf("variant" to "A"))
        val event = GrowthDebugSink.recent().last()
        assertEquals("paywall.opened", event.label)
        assertTrue(event.suppressed)
        assertEquals("A", event.properties["variant"])
        assertNull(event.properties["_ctx"])
    }

    @Test fun `suppressed without debug records nothing`() = runTest {
        GrowthDebugSink.clear()
        val a = analytics(config(debug = false), CountingHttpClient(), FakeProbe(debuggable = true))
        a.track("x")
        assertTrue(GrowthDebugSink.recent().isEmpty())
    }

    @Test fun `sdk version is 0_9_0`() {
        assertNotNull(GrowthAnalytics.SDK_VERSION)
        assertEquals("0.9.0", GrowthAnalytics.SDK_VERSION)
    }
}

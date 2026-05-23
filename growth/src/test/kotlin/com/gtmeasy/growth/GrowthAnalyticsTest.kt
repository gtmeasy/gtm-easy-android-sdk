package com.gtmeasy.growth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingHttpClient(private val response: GrowthHttpResponse = GrowthHttpResponse(201, "{\"event\":{\"id\":\"evt_1\",\"eventName\":\"app.first_open\"}}")) : GrowthHttpClient {
    val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GrowthHttpResponse {
        calls += Triple(url, headers, body)
        return response
    }
}

private class FixedAnonymousIdStore(private var id: String) : AnonymousIdStore {
    var rotated = false
    override fun get(): String = id
    override fun rotate(): String {
        rotated = true
        id = "anon_rotated"
        return id
    }
}

class GrowthAnalyticsTest {
    private val configuration = GrowthAnalyticsConfiguration(
        app = "test-app",
        endpoint = "https://example.gtmeasy.com",
        writeKey = "gte_test",
        environment = GrowthAnalyticsConfiguration.Environment.STAGING,
    )

    @Test fun `configuration defaults endpoint to production`() {
        val config = GrowthAnalyticsConfiguration(app = "test-app", writeKey = "gte_test")
        assertEquals("https://www.gtmeasy.com", config.endpoint)
        assertEquals(GrowthAnalyticsConfiguration.DEFAULT_ENDPOINT, config.endpoint)
    }

    @Test fun `track posts to events endpoint with auth header`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))

        analytics.track("paywall.opened", mapOf("variant" to "A"))

        val call = client.calls.single()
        assertEquals("https://example.gtmeasy.com/api/v1/growth/events", call.first)
        assertEquals("gte_test", call.second["x-gtm-growth-key"])
        assertEquals("application/json", call.second["content-type"])
        val body = Json.parseToJsonElement(call.third).jsonObject
        assertEquals("test-app", body.stringField("app"))
        assertEquals("staging", body.stringField("environment"))
        assertEquals("paywall.opened", body.stringField("eventName"))
        assertEquals("anon_1", body.stringField("anonymousId"))
        assertEquals("A", (body["properties"] as JsonObject).stringField("variant"))
    }

    @Test fun `identify persists userId between calls`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))

        analytics.identify("user_123", mapOf("plan" to "pro"))
        analytics.track("feature.used")

        val identifyBody = Json.parseToJsonElement(client.calls[0].third).jsonObject
        val trackBody = Json.parseToJsonElement(client.calls[1].third).jsonObject
        assertEquals("user_123", identifyBody.stringField("userId"))
        assertEquals("user_123", trackBody.stringField("userId"))
    }

    @Test fun `identify sends first-class username and email`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))

        analytics.identify("user_123", username = "john_wayne", email = "  John@Example.com ")

        val body = Json.parseToJsonElement(client.calls.single().third).jsonObject
        assertEquals("john_wayne", body.stringField("username"))
        // Client trims; the server lowercases. We keep trimmed plaintext here.
        assertEquals("John@Example.com", body.stringField("email"))
    }

    @Test fun `identity persists across instances via the identity store`() = runTest {
        val store = InMemoryIdentityStore()
        val first = GrowthAnalytics(configuration, RecordingHttpClient(), FixedAnonymousIdStore("anon_1"), identityStore = store)
        first.identify("user_123", username = "jw", email = "jw@example.com")

        // Simulate a relaunch: fresh instance, same durable identity store.
        val client = RecordingHttpClient()
        val second = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"), identityStore = store)
        assertEquals("user_123", second.getUserId())
        second.track("paywall.opened")
        val body = Json.parseToJsonElement(client.calls.single().third).jsonObject
        assertEquals("user_123", body.stringField("userId"))
    }

    @Test fun `reset clears identity and rotates the anonymous id`() = runTest {
        val client = RecordingHttpClient()
        val anon = FixedAnonymousIdStore("anon_1")
        val analytics = GrowthAnalytics(configuration, client, anon, identityStore = InMemoryIdentityStore())
        analytics.identify("user_123", email = "u@example.com")

        analytics.reset()

        assertNull(analytics.getUserId())
        assertNull(analytics.getEmail())
        assertTrue(anon.rotated)
    }

    @Test fun `bridges are notified after a successful track`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        var trackedEvent: String? = null
        analytics.bridges += object : GrowthBridge {
            override val name = "test"
            override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
                trackedEvent = eventName
            }
        }
        analytics.track("purchase.completed")
        assertEquals("purchase.completed", trackedEvent)
    }

    @Test fun `bridges that throw never break the SDK`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.bridges += object : GrowthBridge {
            override val name = "broken"
            override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
                throw IllegalStateException("kaboom")
            }
        }
        // Should not raise.
        analytics.track("paywall.opened")
        assertEquals(1, client.calls.size)
    }

    @Test fun `non-2xx response raises GrowthAnalyticsException`() = runTest {
        val client = RecordingHttpClient(GrowthHttpResponse(401, "{\"error\":\"nope\"}"))
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        try {
            analytics.track("paywall.opened")
            assert(false) { "Expected exception" }
        } catch (e: GrowthAnalyticsException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.responseBody?.contains("nope") == true)
        }
    }

    @Test fun `trackPurchaseCompleted sets metric value and currency`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.trackPurchaseCompleted(9.99, "USD", "pro_monthly")
        val body = Json.parseToJsonElement(client.calls.single().third).jsonObject
        assertEquals(9.99, body.doubleField("metricValue"), 1e-9)
        assertEquals("USD", body.stringField("metricLabel"))
        val properties = body["properties"] as JsonObject
        assertEquals("USD", properties.stringField("currency"))
        assertEquals("pro_monthly", properties.stringField("productId"))
    }

    @Test fun `submitPlayInstallReferrer hits the attribution endpoint`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.submitPlayInstallReferrer("utm_source=twitter&utm_campaign=demo")
        val call = client.calls.single()
        assertEquals("https://example.gtmeasy.com/api/v1/growth/attribution/play-install-referrer", call.first)
        val body = Json.parseToJsonElement(call.third).jsonObject
        assertEquals("utm_source=twitter&utm_campaign=demo", body.stringField("playInstallReferrer"))
    }

    @Test fun `IngestResponse exposes parsed event id`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        val response = analytics.track("app.first_open")
        assertEquals("evt_1", response.eventId)
        assertEquals("app.first_open", response.eventName)
    }

    @Test fun `track without setting userId omits it from payload`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.track("page.viewed")
        val body = Json.parseToJsonElement(client.calls.single().third).jsonObject
        assertNull(body["userId"])
        assertNotNull(body["anonymousId"])
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.doubleField(key: String): Double =
        (this[key] as JsonPrimitive).content.toDouble()
}

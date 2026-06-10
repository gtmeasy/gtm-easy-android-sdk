package com.gtmeasy.growth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    @Test fun `submitSurvey posts typed answers and parses the ack`() = runTest {
        val client = RecordingHttpClient(GrowthHttpResponse(201, "{\"submissionId\":\"sub_1\",\"accepted\":3,\"warnings\":[]}"))
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.setUserId("user_9")

        val result = analytics.submitSurvey(
            surveyId = "onboarding_v1",
            surveyName = "Onboarding",
            surveyVersion = "2",
            responses = listOf(
                SurveyAnswers.singleChoice("source", "tiktok", label = "TikTok", questionText = "Where?", metadata = mapOf("ms" to 640)),
                SurveyAnswers.rating("satisfaction", 5),
                SurveyAnswers.text("goal", "Track screen time"),
            ),
            metadata = mapOf("variant" to "B", "flow" to "paywall_first"),
        )

        assertEquals("sub_1", result.submissionId)
        assertEquals(3, result.accepted)

        val call = client.calls.single()
        assertEquals("https://example.gtmeasy.com/api/v1/growth/surveys", call.first)
        val body = Json.parseToJsonElement(call.third).jsonObject
        assertEquals("onboarding_v1", body.stringField("surveyId"))
        assertEquals("completed", body.stringField("status"))
        assertEquals("user_9", body.stringField("userId"))
        // The SDK mints the idempotency key on-device (no caller value) and SENDS
        // it, so a transparent retry reuses the same key for server dedup.
        assertEquals(36, body.stringField("submissionId")?.length)
        // Common context rides under properties._ctx, exactly like track().
        val props = body["properties"] as JsonObject
        assertEquals("gtm-easy-kotlin", (props["_ctx"] as JsonObject).stringField("sdk"))
        val responses = body["responses"] as JsonArray
        assertEquals(3, responses.size)
        val first = responses[0].jsonObject
        assertEquals("single_choice", first.stringField("type"))
        assertEquals("tiktok", (first["choices"] as JsonArray)[0].let { (it as JsonPrimitive).content })
        // explicitNulls = false: an omitted answer field must not appear.
        assertNull(responses[2].jsonObject["choices"])
        // Submission-level metadata echoed onto the body; per-answer metadata on
        // the answer row; answers without metadata omit the key (explicitNulls=false).
        val metadata = body["metadata"] as JsonObject
        assertEquals("B", metadata.stringField("variant"))
        assertEquals("paywall_first", metadata.stringField("flow"))
        assertEquals(640, (first["metadata"] as JsonObject)["ms"].let { (it as JsonPrimitive).content.toInt() })
        assertNull(responses[1].jsonObject["metadata"])
    }

    @Test fun `submitSurvey falls back to the supplied submissionId`() = runTest {
        val client = RecordingHttpClient(GrowthHttpResponse(201, "{\"accepted\":1,\"warnings\":[\"x\"]}"))
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        val result = analytics.submitSurvey(
            surveyId = "s",
            submissionId = "sub_client",
            responses = listOf(SurveyAnswers.text("q1", "a")),
        )
        assertEquals("sub_client", result.submissionId)
        assertEquals(listOf("x"), result.warnings)
    }

    @Test fun `trackSurveyShown emits a survey_shown event`() = runTest {
        val client = RecordingHttpClient(GrowthHttpResponse(201, "{\"event\":{\"id\":\"e\",\"eventName\":\"survey.shown\"}}"))
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.trackSurveyShown(surveyId = "onboarding_v1", surveyName = "Onboarding")
        val call = client.calls.single()
        assertEquals("https://example.gtmeasy.com/api/v1/growth/events", call.first)
        val body = Json.parseToJsonElement(call.third).jsonObject
        assertEquals("survey.shown", body.stringField("eventName"))
        val props = body["properties"] as JsonObject
        assertEquals("onboarding_v1", props.stringField("survey_id"))
    }

    @Test fun `track without setting userId omits it from payload`() = runTest {
        val client = RecordingHttpClient()
        val analytics = GrowthAnalytics(configuration, client, FixedAnonymousIdStore("anon_1"))
        analytics.track("page.viewed")
        val body = Json.parseToJsonElement(client.calls.single().third).jsonObject
        assertNull(body["userId"])
        assertNotNull(body["anonymousId"])
    }

    @Test fun `disabled configuration suppresses all network calls`() = runTest {
        val client = RecordingHttpClient()
        val disabledConfig = configuration.copy(disabled = true)
        val analytics = GrowthAnalytics(disabledConfig, client, FixedAnonymousIdStore("anon_1"))

        val identifyRes = analytics.identify("user_123", mapOf("plan" to "pro"))
        val trackRes = analytics.track("paywall.opened")
        val playRes = analytics.submitPlayInstallReferrer("utm_source=test")
        val surveyRes = analytics.submitSurvey(
            surveyId = "s1",
            responses = listOf(SurveyAnswers.text("q", "a")),
        )

        assertTrue(client.calls.isEmpty())
        assertNull(identifyRes.eventId)
        assertNull(trackRes.eventId)
        assertNull(playRes.eventId)
        assertEquals(0, surveyRes.accepted)
        assertTrue(surveyRes.submissionId.isNotBlank())
    }

    @Test fun `disabled echoes caller-supplied submissionId`() = runTest {
        val disabledConfig = configuration.copy(disabled = true)
        val analytics = GrowthAnalytics(disabledConfig, RecordingHttpClient(), FixedAnonymousIdStore("anon_1"))
        val res = analytics.submitSurvey(
            surveyId = "s1",
            submissionId = "caller-id",
            responses = listOf(),
        )
        assertEquals("caller-id", res.submissionId)
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.doubleField(key: String): Double =
        (this[key] as JsonPrimitive).content.toDouble()
}

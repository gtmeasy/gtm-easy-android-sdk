package com.gtmeasy.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrowthBridgeTest {
    @Test fun `clarity bridge uses userId when present otherwise anonymousId`() {
        var seen: String? = null
        val bridge = ClarityBridge(setCustomUserId = { seen = it })
        bridge.onIdentify("u1", "anon", null, null, emptyMap())
        assertEquals("u1", seen)

        bridge.onIdentify(null, "anon", null, null, emptyMap())
        assertEquals("anon", seen)
    }

    @Test fun `clarity bridge forwards email username and traits as string tags`() {
        val tags = mutableMapOf<String, String>()
        val bridge = ClarityBridge(setCustomUserId = {}, setCustomTag = { k, v -> tags[k] = v })
        bridge.onIdentify("u1", "anon", "john", "john@example.com", mapOf("plan" to "pro", "missing" to null))
        assertEquals("pro", tags["plan"])
        assertEquals("john", tags["username"])
        assertEquals("john@example.com", tags["email"])
        assertNull(tags["missing"])
    }

    @Test fun `posthog bridge forwards capture events and merges email plus name`() {
        val captured = mutableListOf<String>()
        var identifiedProps: Map<String, Any?> = emptyMap()
        var didReset = false
        val bridge = PostHogBridge(
            identify = { _, traits -> identifiedProps = traits },
            capture = { e, _ -> captured += e },
            reset = { didReset = true },
        )
        bridge.onIdentify("u1", "anon", "john", "john@example.com", mapOf("plan" to "pro"))
        bridge.onTrack("paywall.opened", emptyMap(), "u1", "anon")
        bridge.onReset()
        assertEquals(listOf("paywall.opened"), captured)
        assertEquals("john@example.com", identifiedProps["email"])
        assertEquals("john", identifiedProps["name"])
        assertEquals("pro", identifiedProps["plan"])
        assertEquals(true, didReset)
    }

    @Test fun `sentry bridge sets user id`() {
        var seen: String? = null
        val bridge = SentryBridge(setUser = { seen = it })
        bridge.onIdentify("user_123", "anon", null, null, emptyMap())
        assertEquals("user_123", seen)
    }

    @Test fun `sentry bridge forwards username and email and clears on reset`() {
        var details: Triple<String, String?, String?>? = null
        var cleared = false
        val bridge = SentryBridge(
            setUser = {},
            setUserDetails = { id, username, email -> details = Triple(id, username, email) },
            clearUser = { cleared = true },
        )
        bridge.onIdentify("u1", "anon", "john", "john@example.com", emptyMap())
        bridge.onReset()
        assertEquals(Triple("u1", "john", "john@example.com"), details)
        assertEquals(true, cleared)
    }

    @Test fun `statsig bridge updates user and logs event`() {
        var updated: String? = null
        val events = mutableListOf<String>()
        val bridge = StatsigBridge(
            updateUser = { updated = it },
            logEvent = { e, _ -> events += e },
        )
        bridge.onIdentify("u1", "anon", null, null, emptyMap())
        bridge.onTrack("purchase.completed", mapOf("currency" to "USD"), "u1", "anon")
        assertEquals("u1", updated)
        assertEquals(listOf("purchase.completed"), events)
    }
}

package com.gtmeasy.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrowthBridgeTest {
    @Test fun `clarity bridge uses userId when present otherwise anonymousId`() {
        var seen: String? = null
        val bridge = ClarityBridge(setCustomUserId = { seen = it })
        bridge.onIdentify("u1", "anon", emptyMap())
        assertEquals("u1", seen)

        bridge.onIdentify(null, "anon", emptyMap())
        assertEquals("anon", seen)
    }

    @Test fun `clarity bridge forwards traits as string tags`() {
        val tags = mutableMapOf<String, String>()
        val bridge = ClarityBridge(setCustomUserId = {}, setCustomTag = { k, v -> tags[k] = v })
        bridge.onIdentify("u1", "anon", mapOf("plan" to "pro", "missing" to null))
        assertEquals("pro", tags["plan"])
        assertNull(tags["missing"])
    }

    @Test fun `posthog bridge forwards capture events`() {
        val captured = mutableListOf<String>()
        val bridge = PostHogBridge(
            identify = { _, _ -> },
            capture = { e, _ -> captured += e },
        )
        bridge.onTrack("paywall.opened", emptyMap(), "u1", "anon")
        assertEquals(listOf("paywall.opened"), captured)
    }

    @Test fun `sentry bridge sets user id`() {
        var seen: String? = null
        val bridge = SentryBridge(setUser = { seen = it })
        bridge.onIdentify("user_123", "anon", emptyMap())
        assertEquals("user_123", seen)
    }

    @Test fun `statsig bridge updates user and logs event`() {
        var updated: String? = null
        val events = mutableListOf<String>()
        val bridge = StatsigBridge(
            updateUser = { updated = it },
            logEvent = { e, _ -> events += e },
        )
        bridge.onIdentify("u1", "anon", emptyMap())
        bridge.onTrack("purchase.completed", mapOf("currency" to "USD"), "u1", "anon")
        assertEquals("u1", updated)
        assertEquals(listOf("purchase.completed"), events)
    }
}

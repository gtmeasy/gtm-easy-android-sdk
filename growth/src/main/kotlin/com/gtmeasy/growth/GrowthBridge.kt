package com.gtmeasy.growth

/**
 * Pluggable bridge that mirrors identify + track calls into a third-party SDK
 * (Microsoft Clarity, PostHog, Sentry, Statsig, …) that the host app already ships.
 *
 * We never depend on those SDKs directly; instead the host app supplies thin lambdas
 * that adapt to whatever third-party SDK version it uses.
 */
interface GrowthBridge {
    val name: String
    fun onIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {}
    fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {}
}

/** Bridge for Microsoft Clarity. Set up custom user id + key event tag. */
class ClarityBridge(
    private val setCustomUserId: (String) -> Unit,
    private val setCustomTag: ((String, String) -> Unit)? = null,
    private val sendCustomEvent: ((String) -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "clarity"

    override fun onIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {
        runCatching { setCustomUserId(userId ?: anonymousId) }
        setCustomTag?.let { tag -> traits.forEach { (k, v) -> if (v != null) runCatching { tag(k, v.toString()) } } }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        sendCustomEvent?.let { send -> runCatching { send(eventName) } }
    }
}

/** Bridge for PostHog (cloud or self-hosted). */
class PostHogBridge(
    private val identify: (userId: String, traits: Map<String, Any?>) -> Unit,
    private val capture: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "posthog"

    override fun onIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {
        runCatching { identify(userId ?: anonymousId, traits) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        capture?.let { c -> runCatching { c(eventName, properties) } }
    }
}

/** Bridge for Sentry (cloud or self-hosted). */
class SentryBridge(
    private val setUser: (id: String) -> Unit,
    private val captureBreadcrumb: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "sentry"

    override fun onIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {
        runCatching { setUser(userId ?: anonymousId) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        captureBreadcrumb?.let { b -> runCatching { b(eventName, properties) } }
    }
}

/** Bridge for Statsig (server or client SDK). */
class StatsigBridge(
    private val updateUser: (userId: String) -> Unit,
    private val logEvent: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "statsig"

    override fun onIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {
        runCatching { updateUser(userId ?: anonymousId) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        logEvent?.let { l -> runCatching { l(eventName, properties) } }
    }
}

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
    fun onIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {}
    fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {}
    /** Called on logout / [GrowthAnalytics.reset] so the third-party SDK can clear its identity. */
    fun onReset() {}
}

/** Bridge for Microsoft Clarity. Set up custom user id + key event tag. */
class ClarityBridge(
    private val setCustomUserId: (String) -> Unit,
    private val setCustomTag: ((String, String) -> Unit)? = null,
    private val sendCustomEvent: ((String) -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "clarity"

    override fun onIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {
        runCatching { setCustomUserId(userId ?: anonymousId) }
        setCustomTag?.let { tag ->
            email?.let { runCatching { tag("email", it) } }
            username?.let { runCatching { tag("username", it) } }
            traits.forEach { (k, v) -> if (v != null) runCatching { tag(k, v.toString()) } }
        }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        sendCustomEvent?.let { send -> runCatching { send(eventName) } }
    }
}

/** Bridge for PostHog (cloud or self-hosted). */
class PostHogBridge(
    private val identify: (userId: String, traits: Map<String, Any?>) -> Unit,
    private val capture: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
    private val reset: (() -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "posthog"

    override fun onIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {
        // PostHog "person properties": email + name are reserved keys it renders specially.
        val enriched = buildMap<String, Any?> {
            putAll(traits)
            email?.let { put("email", it) }
            username?.let { put("name", it) }
        }
        runCatching { identify(userId ?: anonymousId, enriched) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        capture?.let { c -> runCatching { c(eventName, properties) } }
    }

    override fun onReset() {
        reset?.let { r -> runCatching { r() } }
    }
}

/**
 * Bridge for Sentry (cloud or self-hosted).
 *
 * Provide [setUserDetails] to forward username + email (preferred); [setUser] remains
 * for id-only call sites. [clearUser] is invoked on reset.
 */
class SentryBridge(
    private val setUser: (id: String) -> Unit,
    private val captureBreadcrumb: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
    private val setUserDetails: ((id: String, username: String?, email: String?) -> Unit)? = null,
    private val clearUser: (() -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "sentry"

    override fun onIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {
        val id = userId ?: anonymousId
        val details = setUserDetails
        if (details != null) runCatching { details(id, username, email) }
        else runCatching { setUser(id) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        captureBreadcrumb?.let { b -> runCatching { b(eventName, properties) } }
    }

    override fun onReset() {
        clearUser?.let { c -> runCatching { c() } }
    }
}

/**
 * Bridge for Statsig (server or client SDK).
 *
 * Provide [updateUserDetails] to forward username + email (preferred); [updateUser]
 * remains for id-only call sites. [clearUser] is invoked on reset.
 */
class StatsigBridge(
    private val updateUser: (userId: String) -> Unit,
    private val logEvent: ((event: String, properties: Map<String, Any?>) -> Unit)? = null,
    private val updateUserDetails: ((userId: String, username: String?, email: String?) -> Unit)? = null,
    private val clearUser: (() -> Unit)? = null,
) : GrowthBridge {
    override val name: String = "statsig"

    override fun onIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {
        val id = userId ?: anonymousId
        val details = updateUserDetails
        if (details != null) runCatching { details(id, username, email) }
        else runCatching { updateUser(id) }
    }

    override fun onTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        logEvent?.let { l -> runCatching { l(eventName, properties) } }
    }

    override fun onReset() {
        clearUser?.let { c -> runCatching { c() } }
    }
}

package com.gtmeasy.growth

import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class GrowthAnalyticsException(val statusCode: Int, val responseBody: String?) :
    RuntimeException("Growth analytics rejected ingest: status=$statusCode body=$responseBody")

/**
 * Send growth analytics events to the GTM Easy ingestion API.
 *
 * The same instance is safe to use from multiple coroutines. Each call hits the
 * network exactly once and returns an [IngestResponse] containing the event id.
 *
 * Bridges registered via [bridges] mirror each identify / track into third-party SDKs
 * that the host app already ships (Clarity / PostHog / Sentry / Statsig). Bridge
 * failures never bubble up — bridge code is wrapped in [runCatching].
 */
class GrowthAnalytics @JvmOverloads constructor(
    private val configuration: GrowthAnalyticsConfiguration,
    httpClient: GrowthHttpClient? = null,
    anonymousIdStore: AnonymousIdStore? = null,
    deviceIdentifiers: GrowthDeviceIdentifiers? = null,
    clickIdStore: GrowthClickIdStore? = null,
    identityStore: IdentityStore? = null,
) {
    private val http: GrowthHttpClient = httpClient ?: UrlConnectionHttpClient(configuration.timeoutMs)
    private val anonStore: AnonymousIdStore = anonymousIdStore ?: configuration.context
        ?.let { SharedPrefsAnonymousIdStore(it) }
        ?: InMemoryAnonymousIdStore()
    private val deviceIds: GrowthDeviceIdentifiers = deviceIdentifiers ?: GrowthDeviceIdentifiers(configuration.context)
    private val clickIds: GrowthClickIdStore = clickIdStore ?: GrowthClickIdStore(configuration.context)
    private val identityStore: IdentityStore = identityStore ?: configuration.context
        ?.let { SharedPrefsIdentityStore(it) }
        ?: InMemoryIdentityStore()
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    // @Volatile ensures cross-thread publication; setUserId / getUserId touch
    // these on arbitrary coroutine dispatchers, and the suspend functions read
    // them without the mutex when building event bodies. Hydrated from the
    // durable identity store at construction so identity survives process death.
    @Volatile private var userId: String? = this.identityStore.get(IdentityStore.KEY_USER_ID)
    @Volatile private var username: String? = this.identityStore.get(IdentityStore.KEY_USERNAME)
    @Volatile private var email: String? = this.identityStore.get(IdentityStore.KEY_EMAIL)

    val bridges: MutableList<GrowthBridge> = CopyOnWriteArrayList()

    /** Force-set the userId without emitting an identify event. Persisted durably. */
    fun setUserId(id: String?) {
        userId = id
        identityStore.set(IdentityStore.KEY_USER_ID, id)
    }

    fun getUserId(): String? = userId

    fun getUsername(): String? = username

    fun getEmail(): String? = email

    /** The configured environment. Read by [GrowthLifecycleObserver] to gate build-only
     *  `app.updated` events outside production. */
    val environment: GrowthAnalyticsConfiguration.Environment get() = configuration.environment

    suspend fun getAnonymousId(): String = anonStore.get()

    /** Direct accessor for click-id capture from deep links. */
    val clickIdStore: GrowthClickIdStore get() = clickIds

    /**
     * Clear the identified user (logout): forget the persisted userId/username/
     * email, rotate the anonymous id, and notify bridges to clear their own
     * identity. Subsequent events start a fresh anonymous stream.
     */
    suspend fun reset() {
        // Rotate the anon id and clear identity together under the one lock that
        // identify()/track() snapshot under, so no concurrent call can observe a
        // torn pair (cleared user + old anon, or old user + rotated anon) and
        // re-stitch a logout-period event onto the previous user.
        mutex.withLock {
            anonStore.rotate()
            userId = null
            username = null
            email = null
            identityStore.set(IdentityStore.KEY_USER_ID, null)
            identityStore.set(IdentityStore.KEY_USERNAME, null)
            identityStore.set(IdentityStore.KEY_EMAIL, null)
        }
        for (bridge in bridges) runCatching { bridge.onReset() }
    }

    /**
     * Identify the current user. `username` and `email` are first-class (not
     * smuggled in `traits`); they sit after `traits` to keep the legacy positional
     * call `identify("u", mapOf(...))` source-compatible. Pass `null` (the default)
     * to leave a field unchanged; use [reset] to clear identity. All three persist.
     */
    suspend fun identify(
        userId: String? = null,
        traits: Map<String, Any?> = emptyMap(),
        username: String? = null,
        email: String? = null,
    ): IngestResponse {
        if (configuration.disabled) return IngestResponse(null, null)
        // Mutate + snapshot identity AND the anon id under one lock so the values
        // we send (and link) can't be torn by a concurrent reset()/track().
        val snap = mutex.withLock {
            if (userId != null) this.userId = userId
            if (username != null) this.username = normalizeIdentity(username)
            if (email != null) this.email = normalizeIdentity(email)
            identityStore.set(IdentityStore.KEY_USER_ID, this.userId)
            identityStore.set(IdentityStore.KEY_USERNAME, this.username)
            identityStore.set(IdentityStore.KEY_EMAIL, this.email)
            IdentitySnapshot(this.userId, this.username, this.email, anonStore.get())
        }
        val enrichedTraits = traits + mapOf("_ctx" to commonContext())
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", snap.userId)
            put("anonymousId", JsonPrimitive(snap.anonymousId))
            putNullable("username", snap.username)
            putNullable("email", snap.email)
            put("platform", JsonPrimitive(detectPlatform()))
            putNullable("appVersion", appVersion())
            putNullable("buildNumber", buildNumber())
            put("locale", JsonPrimitive(Locale.getDefault().toLanguageTag()))
            put("timezone", JsonPrimitive(TimeZone.getDefault().id))
            put("traits", enrichedTraits.toJson())
        }
        if (configuration.debug) {
            GrowthDebugSink.record(
                GrowthDebugSink.DebugEvent(GrowthDebugSink.Kind.IDENTIFY, snap.userId ?: snap.username ?: snap.email ?: "<anonymous>", enrichedTraits)
            )
        }
        notifyBridgesIdentify(snap.userId, snap.anonymousId, snap.username, snap.email, traits)
        return post(body, "/api/v1/growth/users")
    }

    suspend fun track(
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
        metricValue: Double? = null,
        metricLabel: String? = null,
    ): IngestResponse {
        if (configuration.disabled) return IngestResponse(null, null)
        val enrichedProperties = properties + mapOf("_ctx" to commonContext())
        // Snapshot userId + anon id atomically under the same lock identify()/reset()
        // use, so a concurrent reset() can't pair a cleared user with the old anon id
        // (or the old user with the rotated anon id).
        val (snapUserId, snapAnon) = mutex.withLock { userId to anonStore.get() }
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", snapUserId)
            put("anonymousId", JsonPrimitive(snapAnon))
            put("eventName", JsonPrimitive(eventName))
            put("platform", JsonPrimitive(detectPlatform()))
            putNullable("appVersion", appVersion())
            putNullable("buildNumber", buildNumber())
            put("source", JsonPrimitive("native"))
            put("locale", JsonPrimitive(Locale.getDefault().toLanguageTag()))
            put("timezone", JsonPrimitive(TimeZone.getDefault().id))
            put("occurredAt", JsonPrimitive(iso8601Now()))
            put("properties", enrichedProperties.toJson())
            metricValue?.let { put("metricValue", JsonPrimitive(it)) }
            metricLabel?.let { put("metricLabel", JsonPrimitive(it)) }
        }
        if (configuration.debug) {
            GrowthDebugSink.record(
                GrowthDebugSink.DebugEvent(GrowthDebugSink.Kind.TRACK, eventName, enrichedProperties)
            )
        }
        notifyBridgesTrack(eventName, properties, snapUserId, snapAnon)
        return post(body, "/api/v1/growth/events")
    }

    /** Common context attached to every event under `properties._ctx`. */
    private suspend fun commonContext(): Map<String, Any?> {
        val ctx = mutableMapOf<String, Any?>()
        val device = deviceIds.snapshot()
        ctx.putAll(device.asProperties())
        ctx.putAll(clickIds.snapshot())
        ctx["sdk"] = "gtm-easy-kotlin"
        ctx["sdk_version"] = SDK_VERSION
        return ctx
    }

    companion object {
        const val SDK_VERSION = "0.7.1"
    }

    suspend fun trackFirstOpen() = track("app.first_open")
    suspend fun trackAppOpen() = track("app.opened")

    /**
     * Record that the app was updated (or that the SDK first ran on a pre-existing
     * install). Deliberately NOT an install: the server never counts `app.updated` in the
     * install funnel, never alerts on it, and never forwards it to an ad platform.
     * [GrowthLifecycleObserver] emits this for you — call it directly only for custom
     * lifecycle wiring.
     */
    suspend fun trackAppUpdated(
        fromVersion: String?,
        fromBuild: String?,
        toVersion: String?,
        toBuild: String?,
        reason: GrowthUpdateReason,
        isRealUpdate: Boolean,
    ): IngestResponse {
        val properties = buildMap<String, Any?> {
            put("reason", reason.wire)
            put("is_real_update", isRealUpdate)
            fromVersion?.let { put("from_version", it) }
            fromBuild?.let { put("from_build", it) }
            toVersion?.let { put("to_version", it) }
            toBuild?.let { put("to_build", it) }
        }
        return track("app.updated", properties)
    }

    suspend fun trackPurchaseCompleted(
        amount: Double,
        currency: String,
        productId: String? = null,
    ): IngestResponse {
        val properties = buildMap<String, Any?> {
            put("currency", currency)
            productId?.let { put("productId", it) }
        }
        return track("purchase.completed", properties, metricValue = amount, metricLabel = currency)
    }

    /**
     * Submit the Google Play Install Referrer string to the server. The server
     * resolves UTM and campaign metadata and stores the parsed result.
     */
    suspend fun submitPlayInstallReferrer(referrer: String): IngestResponse {
        if (configuration.disabled) return IngestResponse(null, null)
        val (snapUserId, snapAnon) = mutex.withLock { userId to anonStore.get() }
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", snapUserId)
            put("anonymousId", JsonPrimitive(snapAnon))
            put("platform", JsonPrimitive("android"))
            put("source", JsonPrimitive("native"))
            put("occurredAt", JsonPrimitive(iso8601Now()))
            put("playInstallReferrer", JsonPrimitive(referrer))
            put("properties", JsonObject(emptyMap()))
        }
        return post(body, "/api/v1/growth/attribution/play-install-referrer")
    }

    /**
     * Submit a flexible onboarding-survey response. Answers persist to the
     * dedicated survey store (no 240-char truncation) and a
     * `survey.completed`/`survey.dismissed` lifecycle event is recorded
     * ([SurveyStatus.PARTIAL] stores answers without one). Pass [submissionId]
     * to make retries idempotent (one is generated client-side when omitted, so
     * a transparent retry reuses the SAME key). Build [responses] with the
     * [SurveyAnswers] factories.
     */
    suspend fun submitSurvey(
        surveyId: String,
        responses: List<SurveyAnswer>,
        surveyName: String? = null,
        surveyVersion: String? = null,
        status: SurveyStatus = SurveyStatus.COMPLETED,
        submissionId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        metadata: Map<String, Any?> = emptyMap(),
    ): SurveySubmitResponse {
        if (configuration.disabled) return SurveySubmitResponse(
            submissionId ?: UUID.randomUUID().toString().lowercase(), 0, emptyList()
        )
        // Generate the idempotency key on-device so a transparent retry reuses it
        // and the server dedups, instead of minting a fresh UUID per attempt.
        val resolvedSubmissionId = submissionId ?: UUID.randomUUID().toString().lowercase()
        val enrichedProperties = properties + mapOf("_ctx" to commonContext())
        val (snapUserId, snapAnon) = mutex.withLock { userId to anonStore.get() }
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", snapUserId)
            put("anonymousId", JsonPrimitive(snapAnon))
            put("surveyId", JsonPrimitive(surveyId))
            putNullable("surveyName", surveyName)
            putNullable("surveyVersion", surveyVersion)
            put("submissionId", JsonPrimitive(resolvedSubmissionId))
            put("status", JsonPrimitive(status.wire))
            put("platform", JsonPrimitive(detectPlatform()))
            putNullable("appVersion", appVersion())
            put("locale", JsonPrimitive(Locale.getDefault().toLanguageTag()))
            put("occurredAt", JsonPrimitive(iso8601Now()))
            put("responses", json.encodeToJsonElement(ListSerializer(SurveyAnswer.serializer()), responses))
            put("properties", enrichedProperties.toJson())
            // Submission-level extensibility payload echoed onto every answer row.
            put("metadata", metadata.toJson())
        }
        if (configuration.debug) {
            GrowthDebugSink.record(
                GrowthDebugSink.DebugEvent(GrowthDebugSink.Kind.TRACK, "survey:$surveyId", mapOf("status" to status.wire))
            )
        }
        // postRaw throws on non-2xx — call it OUTSIDE runCatching so the error
        // propagates instead of being swallowed by the parse guard.
        val responseBody = postRaw(body, "/api/v1/growth/surveys")
        val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObjectOrNull() }.getOrNull()
        val ackSubmissionId = parsed?.get("submissionId")?.primitiveStringOrNull() ?: resolvedSubmissionId
        val accepted = parsed?.get("accepted")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
        val warnings = (parsed?.get("warnings") as? JsonArray)?.mapNotNull { it.primitiveStringOrNull() } ?: emptyList()
        return SurveySubmitResponse(ackSubmissionId, accepted, warnings)
    }

    private suspend fun post(body: JsonObject, path: String): IngestResponse {
        // postRaw throws on non-2xx — keep it OUTSIDE runCatching (which only
        // guards JSON parsing) so ingest rejections surface as exceptions.
        val responseBody = postRaw(body, path)
        val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObjectOrNull() }.getOrNull()
        val eventId = parsed?.get("event")?.jsonObjectOrNull()?.get("id")?.primitiveStringOrNull()
        val eventName = parsed?.get("event")?.jsonObjectOrNull()?.get("eventName")?.primitiveStringOrNull()
        return IngestResponse(eventId, eventName)
    }

    /** Shared transport: POST [body] to [path], throw on non-2xx, return the raw response body. */
    private suspend fun postRaw(body: JsonObject, path: String): String {
        val url = configuration.endpoint.trimEnd('/') + path
        val headers = buildMap {
            put("content-type", "application/json")
            put("x-gtm-growth-key", configuration.writeKey)
            configuration.userAgent?.let { put("user-agent", it) }
        }
        val response = http.post(url, headers, json.encodeToString(JsonObject.serializer(), body))
        if (response.status !in 200..299) {
            throw GrowthAnalyticsException(response.status, response.body)
        }
        return response.body
    }

    private fun notifyBridgesIdentify(userId: String?, anonymousId: String, username: String?, email: String?, traits: Map<String, Any?>) {
        for (bridge in bridges) runCatching { bridge.onIdentify(userId, anonymousId, username, email, traits) }
    }

    /** Trim an identity field; collapse empty / whitespace-only input to null. */
    private fun normalizeIdentity(value: String): String? = value.trim().ifEmpty { null }

    private fun notifyBridgesTrack(eventName: String, properties: Map<String, Any?>, userId: String?, anonymousId: String) {
        for (bridge in bridges) runCatching {
            bridge.onTrack(eventName, properties, userId, anonymousId)
        }
    }

    /** Atomic snapshot of identity + anon id, captured under the mutex. */
    private data class IdentitySnapshot(
        val userId: String?,
        val username: String?,
        val email: String?,
        val anonymousId: String,
    )

    private fun detectPlatform(): String = if (configuration.context != null) "android" else "server"

    private fun appVersion(): String? = configuration.context?.let { ctx ->
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    }

    private fun buildNumber(): String? = configuration.context?.let { ctx ->
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString()
            else @Suppress("DEPRECATION") info.versionCode.toString()
        }.getOrNull()
    }

    private fun iso8601Now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}

data class IngestResponse(val eventId: String?, val eventName: String?)

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.primitiveStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private inline fun buildJsonObject(builder: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
    val map = mutableMapOf<String, JsonElement>()
    map.builder()
    return JsonObject(map)
}

private fun MutableMap<String, JsonElement>.putNullable(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

internal fun Map<String, Any?>.toJson(): JsonObject = JsonObject(mapValues { (_, v) -> v.toJson() })

@Suppress("UNCHECKED_CAST")
internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> (this as Map<String, Any?>).toJson()
    is Iterable<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(this.toString())
}

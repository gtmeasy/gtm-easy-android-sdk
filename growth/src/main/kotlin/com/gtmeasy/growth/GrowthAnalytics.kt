package com.gtmeasy.growth

import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val http: GrowthHttpClient = httpClient ?: UrlConnectionHttpClient(configuration.timeoutMs)
    private val anonStore: AnonymousIdStore = anonymousIdStore ?: configuration.context
        ?.let { SharedPrefsAnonymousIdStore(it) }
        ?: InMemoryAnonymousIdStore()
    private val deviceIds: GrowthDeviceIdentifiers = deviceIdentifiers ?: GrowthDeviceIdentifiers(configuration.context)
    private val clickIds: GrowthClickIdStore = clickIdStore ?: GrowthClickIdStore(configuration.context)
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private var userId: String? = null

    val bridges: MutableList<GrowthBridge> = CopyOnWriteArrayList()

    /** Force-set the userId without emitting an identify event. */
    fun setUserId(id: String?) {
        userId = id
    }

    fun getUserId(): String? = userId

    suspend fun getAnonymousId(): String = anonStore.get()

    /** Direct accessor for click-id capture from deep links. */
    val clickIdStore: GrowthClickIdStore get() = clickIds

    suspend fun identify(userId: String? = null, traits: Map<String, Any?> = emptyMap()): IngestResponse {
        val effectiveUserId = mutex.withLock {
            if (userId != null) this.userId = userId
            this.userId
        }
        val enrichedTraits = traits + mapOf("_ctx" to commonContext())
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", effectiveUserId)
            put("anonymousId", JsonPrimitive(anonStore.get()))
            put("platform", JsonPrimitive(detectPlatform()))
            putNullable("appVersion", appVersion())
            putNullable("buildNumber", buildNumber())
            put("locale", JsonPrimitive(Locale.getDefault().toLanguageTag()))
            put("timezone", JsonPrimitive(TimeZone.getDefault().id))
            put("traits", enrichedTraits.toJson())
        }
        if (configuration.debug) {
            GrowthDebugSink.record(
                GrowthDebugSink.DebugEvent(GrowthDebugSink.Kind.IDENTIFY, effectiveUserId ?: "<anonymous>", enrichedTraits)
            )
        }
        notifyBridgesIdentify(effectiveUserId, anonStore.get(), traits)
        return post(body, "/api/v1/growth/users")
    }

    suspend fun track(
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
        metricValue: Double? = null,
        metricLabel: String? = null,
    ): IngestResponse {
        val enrichedProperties = properties + mapOf("_ctx" to commonContext())
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", userId)
            put("anonymousId", JsonPrimitive(anonStore.get()))
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
        notifyBridgesTrack(eventName, properties)
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
        const val SDK_VERSION = "0.2.0"
    }

    suspend fun trackFirstOpen() = track("app.first_open")
    suspend fun trackAppOpen() = track("app.opened")

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
        val body = buildJsonObject {
            put("app", JsonPrimitive(configuration.app))
            put("environment", JsonPrimitive(configuration.environment.wire))
            putNullable("userId", userId)
            put("anonymousId", JsonPrimitive(anonStore.get()))
            put("platform", JsonPrimitive("android"))
            put("source", JsonPrimitive("native"))
            put("occurredAt", JsonPrimitive(iso8601Now()))
            put("playInstallReferrer", JsonPrimitive(referrer))
            put("properties", JsonObject(emptyMap()))
        }
        return post(body, "/api/v1/growth/attribution/play-install-referrer")
    }

    private suspend fun post(body: JsonObject, path: String): IngestResponse {
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
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObjectOrNull() }.getOrNull()
        val eventId = parsed?.get("event")?.jsonObjectOrNull()?.get("id")?.primitiveStringOrNull()
        val eventName = parsed?.get("event")?.jsonObjectOrNull()?.get("eventName")?.primitiveStringOrNull()
        return IngestResponse(eventId, eventName)
    }

    private fun notifyBridgesIdentify(userId: String?, anonymousId: String, traits: Map<String, Any?>) {
        for (bridge in bridges) runCatching { bridge.onIdentify(userId, anonymousId, traits) }
    }

    private fun notifyBridgesTrack(eventName: String, properties: Map<String, Any?>) {
        for (bridge in bridges) runCatching {
            bridge.onTrack(eventName, properties, userId, anonStore.get())
        }
    }

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

private fun Map<String, Any?>.toJson(): JsonObject = JsonObject(mapValues { (_, v) -> v.toJson() })

@Suppress("UNCHECKED_CAST")
private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> (this as Map<String, Any?>).toJson()
    is Iterable<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(this.toString())
}

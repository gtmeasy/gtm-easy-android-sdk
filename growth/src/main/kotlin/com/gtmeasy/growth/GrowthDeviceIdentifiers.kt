package com.gtmeasy.growth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Device-level common context: GAID (Advertising ID), Android ID alternative,
 * and limit-ad-tracking state.
 *
 * GAID is read via **reflection** so the SDK does not hard-depend on
 * `play-services-ads-identifier`. If the host app adds it
 * (`com.google.android.gms:play-services-ads-identifier:18.x`) we pick up the
 * id; otherwise we return null and rely on `anonymousId` only.
 *
 * Network/main thread safety: callers MUST invoke from a coroutine — the
 * lookup is dispatched to [Dispatchers.IO] internally with a 1s timeout so
 * a misbehaving Play Services never blocks event submission.
 */
class GrowthDeviceIdentifiers(private val context: Context?) {

    suspend fun snapshot(): DeviceSnapshot {
        val gaid = readGaidWithTimeout()
        val limitAdTracking = gaid?.limitAdTracking ?: true
        // CRITICAL: suppress GAID when limit-ad-tracking is on. Some Play
        // Services builds still return the real id even after the user opts
        // out — propagating that downstream (e.g. Meta CAPI `madid`) would
        // violate the user's privacy choice + Google's policy.
        val effectiveGaid = if (limitAdTracking) null else gaid?.id
        return DeviceSnapshot(
            gaid = effectiveGaid,
            limitAdTracking = limitAdTracking,
            androidVersion = android.os.Build.VERSION.RELEASE ?: "",
            manufacturer = android.os.Build.MANUFACTURER ?: "",
            model = android.os.Build.MODEL ?: "",
        )
    }

    private suspend fun readGaidWithTimeout(): GaidResult? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(1000L) { readGaidReflectively() }
    }

    private fun readGaidReflectively(): GaidResult? = runCatching {
        val ctx = context ?: return@runCatching null
        val clientClass = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
        val infoMethod = clientClass.getMethod("getAdvertisingIdInfo", Context::class.java)
        val info = infoMethod.invoke(null, ctx) ?: return@runCatching null
        val id = info.javaClass.getMethod("getId").invoke(info) as? String
        val limit = info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: true
        // Apps that haven't opted out but have ID-collection disabled return
        // 00000000-0000-0000-0000-000000000000 — treat that as null too.
        if (id == null || id == "00000000-0000-0000-0000-000000000000") {
            GaidResult(id = null, limitAdTracking = limit)
        } else {
            GaidResult(id = id, limitAdTracking = limit)
        }
    }.getOrNull()

    private data class GaidResult(val id: String?, val limitAdTracking: Boolean)

    data class DeviceSnapshot(
        val gaid: String?,
        val limitAdTracking: Boolean,
        val androidVersion: String,
        val manufacturer: String,
        val model: String,
    ) {
        fun asProperties(): Map<String, Any?> = buildMap {
            put("limit_ad_tracking", limitAdTracking)
            put("os_version", androidVersion)
            put("device_manufacturer", manufacturer)
            put("device_model", model)
            if (gaid != null) put("gaid", gaid)
        }
    }
}

package com.gtmeasy.growth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlin.random.Random

/**
 * Persists click identifiers captured from deep-link launches, Install
 * Referrer payloads, and ad SDKs. Backed by SharedPreferences keyed by
 * provider — install-scoped. 90-day TTL matches Meta `_fbc` and TikTok
 * `ttclid` retention semantics.
 *
 * Without a [Context] this falls back to an in-memory map; call sites should
 * always pass an `applicationContext` for persistence.
 */
class GrowthClickIdStore(context: Context?) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // The fallback in-memory map is used on JVM / when callers pass a null
    // Context. Multi-coroutine writes (e.g. burst capture from a deep link
    // handler + concurrent track()) require thread-safe storage.
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun record(provider: GrowthClickProvider, value: String, at: Long = System.currentTimeMillis()) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        prefs?.edit()?.putString(keyValue(provider), trimmed)?.putLong(keyTs(provider), at)?.apply()
            ?: run { memory[provider.name] = trimmed to at }
    }

    fun current(provider: GrowthClickProvider, ttlMs: Long = DEFAULT_TTL_MS, now: Long = System.currentTimeMillis()): String? {
        val (value, ts) = prefs?.let {
            val v = it.getString(keyValue(provider), null) ?: return@let null
            val t = it.getLong(keyTs(provider), 0L)
            v to t
        } ?: memory[provider.name] ?: return null
        if (value.isEmpty()) return null
        if (ttlMs != Long.MAX_VALUE && now - ts > ttlMs) return null
        return value
    }

    fun clear(provider: GrowthClickProvider) {
        prefs?.edit()?.remove(keyValue(provider))?.remove(keyTs(provider))?.apply()
            ?: memory.remove(provider.name)
    }

    /**
     * Meta `_fbc` value derived from an inbound fbclid. Format: `fb.1.{ts_ms}.{fbclid}`.
     */
    fun ensureFbc(fbclid: String, at: Long = System.currentTimeMillis()): String? {
        if (fbclid.isEmpty()) return null
        val fbc = "fb.1.$at.$fbclid"
        record(GrowthClickProvider.FBC, fbc, at)
        return fbc
    }

    /**
     * Meta `_fbp` persistent browser-like id. Lives forever per install once
     * generated; only [clear] resets.
     */
    fun ensureFbp(now: Long = System.currentTimeMillis()): String {
        current(GrowthClickProvider.FBP, ttlMs = Long.MAX_VALUE, now = now)?.let { return it }
        val rand = Random.Default.nextLong(1_000_000_000L, Long.MAX_VALUE)
        val value = "fb.1.$now.$rand"
        record(GrowthClickProvider.FBP, value, now)
        return value
    }

    /**
     * Walk a deep-link [uri], persist any known click id query params, and
     * return how many were captured. Mirror of the Swift `captureClickIds`.
     */
    fun captureClickIds(uri: Uri, at: Long = System.currentTimeMillis()): Int {
        var count = 0
        for (name in uri.queryParameterNames) {
            val value = uri.getQueryParameter(name).orEmpty()
            if (value.isEmpty()) continue
            when (name.lowercase()) {
                "fbclid" -> {
                    record(GrowthClickProvider.FBCLID, value, at); ensureFbc(value, at); count++
                }
                "gclid" -> { record(GrowthClickProvider.GCLID, value, at); count++ }
                "wbraid" -> { record(GrowthClickProvider.WBRAID, value, at); count++ }
                "gbraid" -> { record(GrowthClickProvider.GBRAID, value, at); count++ }
                "ttclid" -> { record(GrowthClickProvider.TTCLID, value, at); count++ }
                "igshid" -> { record(GrowthClickProvider.IGSHID, value, at); count++ }
                "msclkid" -> { record(GrowthClickProvider.MSCLKID, value, at); count++ }
                "twclid" -> { record(GrowthClickProvider.TWCLID, value, at); count++ }
            }
        }
        return count
    }

    fun snapshot(now: Long = System.currentTimeMillis()): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (provider in GrowthClickProvider.values()) {
            current(provider, now = now)?.let { out[provider.eventKey] = it }
        }
        out["fbp"] = ensureFbp(now)
        return out
    }

    private fun keyValue(p: GrowthClickProvider) = "${KEY_PREFIX}${p.name.lowercase()}_value"
    private fun keyTs(p: GrowthClickProvider) = "${KEY_PREFIX}${p.name.lowercase()}_ts"

    companion object {
        private const val PREFS_NAME = "gtm_easy_growth_click_ids"
        private const val KEY_PREFIX = "ck_"
        const val DEFAULT_TTL_MS = 90L * 24 * 60 * 60 * 1000L  // 90 days
    }
}

enum class GrowthClickProvider(val eventKey: String) {
    FBC("fbc"),
    FBP("fbp"),
    FBCLID("fbclid"),
    GCLID("gclid"),
    WBRAID("wbraid"),
    GBRAID("gbraid"),
    TTCLID("ttclid"),
    IGSHID("igshid"),
    MSCLKID("msclkid"),
    TWCLID("twclid"),
}

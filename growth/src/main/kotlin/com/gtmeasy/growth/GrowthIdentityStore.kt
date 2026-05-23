package com.gtmeasy.growth

import android.content.Context

/**
 * Durable store for the identified user (userId / username / email). Persisted so
 * a track() after a process death still attributes to the resolved user, matching
 * DataFast's "re-identify on session change" model. Distinct from
 * [AnonymousIdStore], which owns only the anonymous id.
 */
interface IdentityStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)

    companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_EMAIL = "email"
    }
}

/** SharedPreferences-backed identity store. Shares the SDK's prefs file. */
internal class SharedPrefsIdentityStore(context: Context) : IdentityStore {
    private val prefs = context.applicationContext.getSharedPreferences("gtm_easy_growth", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) {
        // commit() (synchronous) so a relaunch reads the durable value immediately.
        if (value == null) prefs.edit().remove(key).commit()
        else prefs.edit().putString(key, value).commit()
    }
}

/** In-memory identity store for tests / server-side use without a Context. */
internal class InMemoryIdentityStore : IdentityStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = synchronized(map) { map[key] }
    override fun set(key: String, value: String?) = synchronized(map) {
        if (value == null) map.remove(key) else map[key] = value
        Unit
    }
}

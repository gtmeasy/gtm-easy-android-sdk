package com.gtmeasy.growth

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

interface AnonymousIdStore {
    fun get(): String

    /**
     * Rotate to a fresh anonymous id (logout / [GrowthAnalytics.reset]) so events
     * emitted afterwards start a new anonymous stream instead of re-stitching onto
     * the previous user. The default no-ops (returns the current id) so custom
     * stores stay source-compatible.
     */
    fun rotate(): String = get()
}

/**
 * Atomic first-init for the persisted anonymous id. Without this, two
 * concurrent first calls from different threads (e.g. lifecycle observer +
 * an in-flight track call) can both miss the persisted value, generate
 * different UUIDs, and split a session into two identities. The cached
 * AtomicReference ensures every caller observes the same id once any thread
 * has resolved it; `commit()` (synchronous) is used instead of `apply()` so
 * the persisted value is durable before any second caller can read.
 */
internal class SharedPrefsAnonymousIdStore(context: Context) : AnonymousIdStore {
    private val prefs = context.applicationContext.getSharedPreferences("gtm_easy_growth", Context.MODE_PRIVATE)
    private val cached = AtomicReference<String?>(prefs.getString(KEY, null))
    private val lock = Any()

    override fun get(): String {
        cached.get()?.let { return it }
        return synchronized(lock) {
            cached.get()?.let { return@synchronized it }
            val persisted = prefs.getString(KEY, null)
            if (persisted != null) {
                cached.set(persisted)
                return@synchronized persisted
            }
            val fresh = UUID.randomUUID().toString().lowercase()
            prefs.edit().putString(KEY, fresh).commit()
            cached.set(fresh)
            fresh
        }
    }

    override fun rotate(): String = synchronized(lock) {
        val fresh = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY, fresh).commit()
        cached.set(fresh)
        fresh
    }
    private companion object { const val KEY = "anonymous_id" }
}

internal class InMemoryAnonymousIdStore : AnonymousIdStore {
    @Volatile private var value = UUID.randomUUID().toString().lowercase()
    override fun get(): String = value
    override fun rotate(): String {
        value = UUID.randomUUID().toString().lowercase()
        return value
    }
}

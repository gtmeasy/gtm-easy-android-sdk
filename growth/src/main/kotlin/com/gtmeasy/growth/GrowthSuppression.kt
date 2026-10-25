package com.gtmeasy.growth

import android.content.Context
import java.util.UUID

/*
 * Memory-only stores used while the SDK is suppressed (disabled, or a test build with
 * suppressTestBuilds). They may READ durable state but never WRITE it, so a later
 * production build on the same device behaves exactly as if the test build never ran.
 */

/**
 * Anonymous id while suppressed: reuse an already-persisted id if [existing] returns one,
 * otherwise generate an in-memory id that is never persisted. [rotate] stays in memory.
 */
internal class MemoryOnlyAnonymousIdStore(existing: () -> String?) : AnonymousIdStore {
    @Volatile private var value: String =
        runCatching(existing).getOrNull() ?: UUID.randomUUID().toString().lowercase()

    override fun get(): String = value

    override fun rotate(): String {
        value = UUID.randomUUID().toString().lowercase()
        return value
    }
}

/** Read the persisted anonymous id written by [SharedPrefsAnonymousIdStore] without creating one. */
internal fun peekPersistedAnonymousId(context: Context): String? =
    context.applicationContext
        .getSharedPreferences(SharedPrefsAnonymousIdStore.PREFS, Context.MODE_PRIVATE)
        .getString(SharedPrefsAnonymousIdStore.KEY, null)

/**
 * Identity store overlay: reads fall through to [base] until a key is set in memory;
 * writes (including clears) only touch memory.
 */
internal class MemoryOverlayIdentityStore(private val base: IdentityStore) : IdentityStore {
    private val overlay = HashMap<String, String?>()

    override fun get(key: String): String? = synchronized(overlay) {
        if (overlay.containsKey(key)) overlay[key] else runCatching { base.get(key) }.getOrNull()
    }

    override fun set(key: String, value: String?) = synchronized(overlay) {
        overlay[key] = value
        Unit
    }
}

package com.gtmeasy.growth

import android.content.Context
import java.util.UUID

internal interface AnonymousIdStore {
    fun get(): String
}

internal class SharedPrefsAnonymousIdStore(context: Context) : AnonymousIdStore {
    private val prefs = context.applicationContext.getSharedPreferences("gtm_easy_growth", Context.MODE_PRIVATE)
    override fun get(): String {
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
    private companion object { const val KEY = "anonymous_id" }
}

internal class InMemoryAnonymousIdStore : AnonymousIdStore {
    private val value = UUID.randomUUID().toString().lowercase()
    override fun get(): String = value
}

package com.gtmeasy.growth

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetch the Google Play Install Referrer string and submit it to GTM Easy.
 *
 * Requires the host app to add `com.android.installreferrer:installreferrer:2.2`
 * (or later) to its dependencies; otherwise this returns null.
 */
suspend fun GrowthAnalytics.collectPlayInstallReferrer(context: Context): IngestResponse? {
    val referrer = readInstallReferrer(context) ?: return null
    return submitPlayInstallReferrer(referrer)
}

private suspend fun readInstallReferrer(context: Context): String? = runCatching {
    val clientClass = Class.forName("com.android.installreferrer.api.InstallReferrerClient")
    val newBuilder = clientClass.getMethod("newBuilder", Context::class.java)
    val builder = newBuilder.invoke(null, context.applicationContext)
    val build = builder.javaClass.getMethod("build")
    val client = build.invoke(builder)

    val listenerClass = Class.forName("com.android.installreferrer.api.InstallReferrerStateListener")
    suspendCancellableCoroutine<String?> { cont ->
        val handler = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onInstallReferrerSetupFinished" -> {
                    val code = args?.get(0) as? Int ?: -1
                    if (code == 0) {
                        val details = client.javaClass.getMethod("getInstallReferrer").invoke(client)
                        val ref = details.javaClass.getMethod("getInstallReferrer").invoke(details) as? String
                        runCatching { client.javaClass.getMethod("endConnection").invoke(client) }
                        cont.resume(ref)
                    } else {
                        cont.resumeWithException(RuntimeException("Play Install Referrer setup failed: $code"))
                    }
                }
                "onInstallReferrerServiceDisconnected" -> {}
            }
            null
        }
        client.javaClass.getMethod("startConnection", listenerClass).invoke(client, handler)
    }
}.getOrNull()

package com.gtmeasy.growth

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Mirror sink for identify / track calls. Wire a debug UI to [events] for
 * a live feed, or query [recent] for a snapshot. Only active when
 * [GrowthAnalyticsConfiguration.debug] is true on the analytics instance.
 */
object GrowthDebugSink {
    private val mutableEvents = MutableSharedFlow<DebugEvent>(replay = 50, extraBufferCapacity = 200)
    val events: SharedFlow<DebugEvent> = mutableEvents.asSharedFlow()
    private val buffer = ArrayDeque<DebugEvent>()
    private const val MAX_BUFFER = 200
    private const val TAG = "GrowthAnalytics"

    fun record(event: DebugEvent) {
        synchronized(buffer) {
            buffer.addLast(event)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        mutableEvents.tryEmit(event)
        Log.d(TAG, "${event.kind} ${event.label} ${event.properties}")
    }

    fun recent(limit: Int = 50): List<DebugEvent> = synchronized(buffer) {
        buffer.toList().takeLast(limit)
    }

    fun clear() = synchronized(buffer) { buffer.clear() }

    enum class Kind { IDENTIFY, TRACK, ATTRIBUTION, ERROR }

    data class DebugEvent(
        val kind: Kind,
        val label: String,
        val properties: Map<String, Any?>,
        val occurredAtMs: Long = System.currentTimeMillis(),
    )
}

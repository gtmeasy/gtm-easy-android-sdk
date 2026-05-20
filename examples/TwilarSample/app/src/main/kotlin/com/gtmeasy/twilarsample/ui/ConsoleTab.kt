package com.gtmeasy.twilarsample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gtmeasy.growth.GrowthDebugSink
import kotlinx.coroutines.delay

/**
 * Live tail of the SDK's debug sink. Configuration must have `debug=true`
 * for events to be mirrored here (see [com.gtmeasy.twilarsample.growth.GrowthClient]).
 */
@Composable
fun ConsoleTab() {
    var events by remember { mutableStateOf(GrowthDebugSink.recent(200)) }

    // Poll every 0.5s. SDK exposes a SharedFlow if you want hot updates.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            events = GrowthDebugSink.recent(200)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = {
                GrowthDebugSink.clear()
                events = emptyList()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear") }

        if (events.isEmpty()) {
            Text(
                "No events yet. Trigger something from another tab and it'll appear here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events.asReversed()) { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "${event.kind.name} · ${event.label}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (event.properties.isNotEmpty()) {
                                Text(
                                    event.properties.entries.joinToString(separator = "\n  ", prefix = "{\n  ", postfix = "\n}") {
                                        "${it.key}: ${it.value}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.gtmeasy.twilarsample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gtmeasy.twilarsample.growth.GrowthClient
import kotlinx.coroutines.launch

/**
 * Lifecycle + auto-instrumentation playground. `GrowthLifecycleObserver` is
 * already registered in [com.gtmeasy.twilarsample.TwilarSampleApp.onCreate]
 * — this tab lets you fire ad-hoc events to confirm fan-out.
 */
@Composable
fun LifecycleTab() {
    val analytics = GrowthClient.require()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    fun run(label: String, body: suspend () -> Unit) {
        scope.launch {
            runCatching { body() }
                .onSuccess { status = "✓ $label" }
                .onFailure { status = "✗ $label: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Manual lifecycle events")
        Text(
            "`GrowthLifecycleObserver` auto-fires `app.first_open` (once per install) and `app.opened` on every activity resume. Use these buttons for ad-hoc events.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = { run("app.first_open") { analytics.trackFirstOpen() } }) {
            Text("Track app.first_open (idempotent)")
        }
        Button(onClick = { run("app.opened") { analytics.trackAppOpen() } }) {
            Text("Track app.opened")
        }

        SectionTitle("Custom events")
        Button(onClick = { run("feature.first_used") {
            analytics.track("feature.first_used", mapOf("feature" to "voice_clone", "session_minutes" to 2))
        } }) { Text("Track feature.first_used") }

        Button(onClick = { run("session.started") {
            analytics.track("session.started", mapOf("source" to "push_notification"))
        } }) { Text("Track session.started") }

        if (status.isNotEmpty()) {
            Card { Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

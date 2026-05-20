package com.gtmeasy.twilarsample.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gtmeasy.growth.GrowthClickProvider
import com.gtmeasy.twilarsample.growth.GrowthClient
import kotlinx.coroutines.launch

@Composable
fun ClickIdsTab() {
    val analytics = GrowthClient.require()
    val scope = rememberCoroutineScope()
    var deepLink by remember {
        mutableStateOf("twilar://onboarding?gclid=demo123&fbclid=demoFB&utm_campaign=spring_sale")
    }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Paste a deep link")
        OutlinedTextField(
            value = deepLink, onValueChange = { deepLink = it },
            label = { Text("twilar://…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val uri = runCatching { Uri.parse(deepLink) }.getOrNull()
            if (uri == null) {
                status = "✗ invalid URI"
                return@Button
            }
            scope.launch {
                val count = analytics.clickIdStore.captureClickIds(uri)
                status = "✓ captured $count click id(s) — next event will include them under properties._ctx"
            }
        }) { Text("Capture click IDs") }

        SectionTitle("Record a specific provider")
        Button(onClick = {
            scope.launch {
                analytics.clickIdStore.record(GrowthClickProvider.GCLID, "test_g_123")
                status = "✓ recorded gclid=test_g_123"
            }
        }) { Text("Record gclid = test_g_123") }
        Button(onClick = {
            scope.launch {
                analytics.clickIdStore.record(GrowthClickProvider.TTCLID, "test_tt_456")
                status = "✓ recorded ttclid=test_tt_456"
            }
        }) { Text("Record ttclid = test_tt_456") }

        SectionTitle("Adb test")
        Text(
            "From a terminal:\n  adb shell am start -W -a android.intent.action.VIEW \\\n  -d \"twilar://onboarding?gclid=adb_demo\" com.gtmeasy.twilarsample",
            style = MaterialTheme.typography.bodySmall,
        )

        if (status.isNotEmpty()) {
            Card { Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

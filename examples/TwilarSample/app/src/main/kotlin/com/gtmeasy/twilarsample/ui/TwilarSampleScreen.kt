package com.gtmeasy.twilarsample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Root screen — a five-tab shell mirroring the iOS sample so the same
 * surfaces are exercised on both platforms.
 *
 *   Funnel    — paywall + purchase events
 *   Identity  — anonymousId, setUserId, identify with traits
 *   ClickIds  — capture from deep links + ASA-style install referrer
 *   Lifecycle — bridges + lifecycle-driven app_opened
 *   Console   — live tail of GrowthDebugSink
 */
@Composable
fun TwilarSampleScreen() {
    MaterialTheme {
        var tab by remember { mutableStateOf(0) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        SAMPLE_TABS.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            ) { padding ->
                Surface(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (tab) {
                        0 -> FunnelTab()
                        1 -> IdentityTab()
                        2 -> ClickIdsTab()
                        3 -> LifecycleTab()
                        4 -> ConsoleTab()
                    }
                }
            }
        }
    }
}

private data class SampleTab(val label: String, val icon: ImageVector)

private val SAMPLE_TABS = listOf(
    SampleTab("Funnel", Icons.Outlined.ArrowForward),
    SampleTab("Identity", Icons.Outlined.AccountCircle),
    SampleTab("Click IDs", Icons.Outlined.Link),
    SampleTab("Lifecycle", Icons.Outlined.Notifications),
    SampleTab("Console", Icons.Outlined.Search),
)

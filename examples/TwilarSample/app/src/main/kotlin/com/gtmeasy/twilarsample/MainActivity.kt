package com.gtmeasy.twilarsample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gtmeasy.twilarsample.growth.GrowthClient
import com.gtmeasy.twilarsample.ui.TwilarSampleScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the activity was launched from a deep link, capture any click ids
        // before we render so the next event already has _ctx populated.
        captureClickIdsFromIntent(intent)
        setContent { TwilarSampleScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureClickIdsFromIntent(intent)
    }

    private fun captureClickIdsFromIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        ioScope.launch {
            GrowthClient.require().clickIdStore.captureClickIds(data)
        }
    }
}

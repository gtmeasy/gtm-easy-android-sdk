package com.gtmeasy.twilarsample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gtmeasy.twilarsample.growth.GrowthClient
import com.gtmeasy.twilarsample.ui.TwilarSampleScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Capture inbound deep-link click ids BEFORE rendering so the very
        // first event fired by the UI already has them in _ctx. The
        // SharedPreferences-backed store writes synchronously (apply() is
        // async but the in-memory map is updated immediately), so calling
        // captureClickIds on the main thread is safe and avoids a race
        // with synchronous tracking calls fired during composition.
        captureClickIdsFromIntent(intent)
        setContent { TwilarSampleScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureClickIdsFromIntent(intent)
    }

    private fun captureClickIdsFromIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        GrowthClient.require().clickIdStore.captureClickIds(data)
    }
}

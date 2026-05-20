package com.gtmeasy.twilarsample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gtmeasy.twilarsample.growth.GrowthClient
import kotlinx.coroutines.launch

@Composable
fun IdentityTab() {
    val analytics = GrowthClient.require()
    val scope = rememberCoroutineScope()
    var anonymousId by remember { mutableStateOf("(loading…)") }
    var userId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        anonymousId = analytics.getAnonymousId()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Anonymous id")
        Text(anonymousId, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { scope.launch { anonymousId = analytics.getAnonymousId() } }) {
            Text("Refresh")
        }

        SectionTitle("Authenticated user")
        OutlinedTextField(
            value = userId, onValueChange = { userId = it },
            label = { Text("user id (e.g. usr_42)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("email (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("phone E.164 (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                scope.launch {
                    val traits = buildMap<String, Any?> {
                        put("signed_up", true)
                        if (email.isNotBlank()) put("email", email)
                        if (phone.isNotBlank()) put("phone", phone)
                    }
                    runCatching {
                        analytics.identify(
                            userId = userId.takeIf { it.isNotBlank() },
                            traits = traits,
                        )
                    }.onSuccess { status = "✓ identify(userId=${userId.ifBlank { "null" }})" }
                     .onFailure { status = "✗ identify: ${it.message}" }
                }
            },
            enabled = userId.isNotBlank() || email.isNotBlank() || phone.isNotBlank(),
        ) { Text("Identify (fires identify event)") }

        Button(onClick = {
            analytics.setUserId(userId.takeIf { it.isNotBlank() })
            status = "✓ setUserId(${userId.ifBlank { "null" }})"
        }) { Text("Set user id only (no event)") }

        if (status.isNotEmpty()) {
            Card { Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

package com.gtmeasy.twilarsample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
import com.gtmeasy.growth.trackPaywallClosed
import com.gtmeasy.growth.trackPaywallOpened
import com.gtmeasy.growth.trackPaywallPlanSelected
import com.gtmeasy.growth.trackPaywallUpgradeCancelled
import com.gtmeasy.growth.trackPaywallUpgradeClicked
import com.gtmeasy.growth.trackRestoreCompleted
import com.gtmeasy.growth.trackTrialStarted
import com.gtmeasy.twilarsample.growth.GrowthClient
import kotlinx.coroutines.launch

/**
 * Walks the canonical paywall funnel:
 *   onboarding.completed → paywall.opened → paywall.plan_selected →
 *   paywall.upgrade_clicked → purchase.completed
 *
 * Each button posts to /api/v1/growth/events; the Console tab mirrors
 * everything via GrowthDebugSink.
 */
@Composable
fun FunnelTab() {
    val analytics = GrowthClient.require()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    val placement = "sample_paywall"
    val productId = "twilar.yearly.49_99"

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
        SectionTitle("Onboarding")
        Button({ run("onboarding.completed") {
            analytics.track("onboarding.completed", mapOf("funnel_variant" to "default"))
        } }) { Text("1. Onboarding completed") }

        Divider()
        SectionTitle("Paywall")
        Button({ run("paywall.opened") {
            analytics.trackPaywallOpened(placement = placement, variant = "annual_first", productIds = listOf(productId))
        } }) { Text("2. Paywall opened") }

        Button({ run("paywall.plan_selected") {
            analytics.trackPaywallPlanSelected(placement = placement, productId = productId, price = 49.99, currency = "USD")
        } }) { Text("3. Plan selected") }

        Button({ run("paywall.upgrade_clicked") {
            analytics.trackPaywallUpgradeClicked(placement = placement, productId = productId, price = 49.99, currency = "USD")
        } }) { Text("4. Upgrade clicked") }

        Button({ run("paywall.upgrade_cancelled") {
            analytics.trackPaywallUpgradeCancelled(placement = placement, productId = productId, reason = "user_cancelled_sheet")
        } }) { Text("4b. Upgrade cancelled (StoreKit dismissed)") }

        Button({ run("paywall.closed") {
            analytics.trackPaywallClosed(placement = placement, reason = "user_dismissed")
        } }) { Text("4c. Paywall closed") }

        Divider()
        SectionTitle("Purchase")
        Button({ run("purchase.completed") {
            analytics.trackPurchaseCompleted(amount = 49.99, currency = "USD", productId = productId)
        } }) { Text("5a. Purchase completed (paid)") }

        Button({ run("trial.started") {
            analytics.trackTrialStarted(productId = productId, trialDurationDays = 7, transactionId = "tx_sample_${System.currentTimeMillis()}")
        } }) { Text("Trial started") }

        Button({ run("paywall.restore_completed") {
            analytics.trackRestoreCompleted(restored = true, productIds = listOf(productId))
        } }) { Text("Restore completed") }

        if (status.isNotEmpty()) {
            Card(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    status,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

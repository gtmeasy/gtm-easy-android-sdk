package com.gtmeasy.growth

/**
 * Typed helpers for the paywall + checkout funnel. Wrap [GrowthAnalytics.track]
 * with the canonical event names (matching the server-side whitelist) and
 * required properties so connectors (Meta CAPI / TikTok / Google Ads) get
 * consistent shapes.
 */

suspend fun GrowthAnalytics.trackPaywallOpened(
    placement: String,
    variant: String? = null,
    productIds: List<String> = emptyList(),
    properties: Map<String, Any?> = emptyMap(),
): IngestResponse {
    val props = properties.toMutableMap().apply {
        put("placement", placement)
        variant?.let { put("variant", it) }
        if (productIds.isNotEmpty()) put("product_ids", productIds)
    }
    return track("paywall.opened", props)
}

suspend fun GrowthAnalytics.trackPaywallPlanSelected(
    placement: String,
    productId: String,
    price: Double? = null,
    currency: String? = null,
    variant: String? = null,
): IngestResponse {
    val props = mutableMapOf<String, Any?>(
        "placement" to placement,
        "product_id" to productId,
    )
    price?.let { props["price"] = it }
    currency?.let { props["currency"] = it }
    variant?.let { props["variant"] = it }
    return track("paywall.plan_selected", props)
}

suspend fun GrowthAnalytics.trackPaywallUpgradeClicked(
    placement: String,
    productId: String,
    price: Double? = null,
    currency: String? = null,
): IngestResponse {
    val props = mutableMapOf<String, Any?>(
        "placement" to placement,
        "product_id" to productId,
    )
    price?.let { props["price"] = it }
    currency?.let { props["currency"] = it }
    return track("paywall.upgrade_clicked", props)
}

suspend fun GrowthAnalytics.trackPaywallUpgradeCancelled(
    placement: String,
    productId: String? = null,
    reason: String? = null,
): IngestResponse {
    val props = mutableMapOf<String, Any?>("placement" to placement)
    productId?.let { props["product_id"] = it }
    reason?.let { props["reason"] = it }
    return track("paywall.upgrade_cancelled", props)
}

suspend fun GrowthAnalytics.trackPaywallClosed(
    placement: String,
    reason: String? = null,
): IngestResponse {
    val props = mutableMapOf<String, Any?>("placement" to placement)
    reason?.let { props["reason"] = it }
    return track("paywall.closed", props)
}

suspend fun GrowthAnalytics.trackTrialStarted(
    productId: String,
    trialDurationDays: Int? = null,
    transactionId: String? = null,
): IngestResponse {
    val props = mutableMapOf<String, Any?>("product_id" to productId)
    trialDurationDays?.let { props["trial_duration_days"] = it }
    transactionId?.let { props["transaction_id"] = it }
    return track("trial.started", props)
}

suspend fun GrowthAnalytics.trackRestoreCompleted(
    restored: Boolean,
    productIds: List<String> = emptyList(),
): IngestResponse {
    val props = mutableMapOf<String, Any?>("restored" to restored)
    if (productIds.isNotEmpty()) props["product_ids"] = productIds
    return track("paywall.restore_completed", props)
}

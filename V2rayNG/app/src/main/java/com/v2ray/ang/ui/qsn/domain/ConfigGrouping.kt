package com.v2ray.ang.ui.qsn.domain

/**
 * One collapsible section of the Configurations screen.
 *
 * [totalConfigs] is the number of configs the subscription actually stores,
 * while [rows] holds only the Cloudflare-ready ones, so the header can say
 * "3 of 11 ready" instead of hiding that the others exist.
 */
data class ConfigGroup(
    val subscriptionId: String,
    val title: String,
    val rows: List<ConfigRow>,
    val totalConfigs: Int,
    val manual: Boolean,
)

/**
 * Groups stored configurations by their origin.
 *
 * The screen only has two kinds of section: one per subscription, and one
 * "Added by You" section for manual imports. There is no third bucket and no
 * cross-subscription leakage: a row is placed by the subscription id stored on
 * the profile, never by list position.
 */
object ConfigGrouping {

    fun groups(
        subscriptions: List<SubscriptionRow>,
        rows: List<ConfigRow>,
        manualTitle: String,
    ): List<ConfigGroup> {
        val known = subscriptions.map { it.guid }.toSet()
        // A config whose subscription no longer exists is not refreshed by
        // anything, so it is managed like a manual one instead of disappearing.
        val manualRows = rows.filter {
            it.source == ConfigSource.Manual || it.subscriptionId !in known
        }
        val result = ArrayList<ConfigGroup>(subscriptions.size + 1)
        subscriptions.forEach { sub ->
            val own = rows.filter { it.subscriptionId == sub.guid && it.guid !in manualRows.map { m -> m.guid } }
            result.add(
                ConfigGroup(
                    subscriptionId = sub.guid,
                    title = sub.displayName,
                    rows = own.filter { it.eligible },
                    totalConfigs = sub.configCount,
                    manual = false,
                )
            )
        }
        if (manualRows.isNotEmpty()) {
            result.add(
                ConfigGroup(
                    subscriptionId = "",
                    title = manualTitle,
                    rows = manualRows,
                    totalConfigs = manualRows.size,
                    manual = true,
                )
            )
        }
        return result
    }
}

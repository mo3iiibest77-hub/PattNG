package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/** Where a stored configuration came from. */
enum class ConfigSource { Manual, Subscription }

/** One row of the Configurations screen. Contains no credential. */
data class ConfigRow(
    val guid: String,
    val remarks: String,
    val protocol: String,
    val network: String,
    val security: String,
    val port: Int,
    val server: String,
    val source: ConfigSource,
    val eligible: Boolean,
    val active: Boolean,
    /** Subscription the profile belongs to; empty for a manual config. */
    val subscriptionId: String = "",
) {
    /** Short transport description such as "vless · ws · tls · 443". */
    val transportLabel: String
        get() = listOf(
            protocol.lowercase(),
            network.ifBlank { "tcp" }.lowercase(),
            security.ifBlank { "none" }.lowercase(),
            port.toString(),
        ).joinToString(" \u00b7 ")

    companion object {
        /**
         * Builds a row from a stored profile.
         *
         * Eligibility is not guessed here: it comes from the same rules the
         * scanner uses, so the badge in the list and the auto-connect filter
         * can never disagree.
         */
        fun from(
            guid: String,
            profile: ProfileItem,
            source: ConfigSource,
            active: Boolean,
            /** Comes from the authoritative filter so badge and flow agree. */
            eligible: Boolean,
        ): ConfigRow {
            val port = profile.serverPort?.toIntOrNull() ?: 0
            return ConfigRow(
                guid = guid,
                remarks = profile.remarks.ifBlank { profile.server.orEmpty() },
                protocol = profile.configType.name,
                network = profile.network.orEmpty(),
                security = profile.security.orEmpty(),
                port = port,
                server = profile.server.orEmpty(),
                source = source,
                eligible = eligible,
                active = active,
                subscriptionId = if (source == ConfigSource.Subscription) {
                    profile.subscriptionId
                } else {
                    ""
                },
            )
        }
    }
}

/** Filter chips of the Configurations screen. */
enum class ConfigFilter {
    All,
    CloudflareEligible,
    WebSocketTls,
    XhttpTls,
    OtherTransport,
    Manual,
    Subscription,
    Active,
    NotEligible,
}

/**
 * Pure filter engine for the configuration list.
 *
 * Filters combine with AND, so "Subscription" + "CloudflareEligible" means
 * exactly the subscription configs that the scanner can use. Selecting [All]
 * clears the others; the UI enforces that, and [apply] treats an empty set as
 * [All] as well.
 *
 * Filtering never mutates storage: the rows passed in are the full set.
 */
object ConfigFilterEngine {

    fun matches(filter: ConfigFilter, row: ConfigRow): Boolean = when (filter) {
        ConfigFilter.All -> true
        ConfigFilter.CloudflareEligible -> row.eligible
        ConfigFilter.NotEligible -> !row.eligible
        ConfigFilter.WebSocketTls -> row.network.equals("ws", true) && row.security.equals("tls", true)
        ConfigFilter.XhttpTls -> row.network.equals("xhttp", true) && row.security.equals("tls", true)
        ConfigFilter.OtherTransport -> !(row.network.equals("ws", true) || row.network.equals("xhttp", true)) ||
            !row.security.equals("tls", true)
        ConfigFilter.Manual -> row.source == ConfigSource.Manual
        ConfigFilter.Subscription -> row.source == ConfigSource.Subscription
        ConfigFilter.Active -> row.active
    }

    fun apply(selected: Set<ConfigFilter>, rows: List<ConfigRow>): List<ConfigRow> {
        val effective = selected.filter { it != ConfigFilter.All }
        if (effective.isEmpty()) return rows
        return rows.filter { row -> effective.all { filter -> matches(filter, row) } }
    }

    /** Toggles one chip, keeping [All] exclusive. */
    fun toggle(selected: Set<ConfigFilter>, filter: ConfigFilter): Set<ConfigFilter> {
        if (filter == ConfigFilter.All) return emptySet()
        return if (selected.contains(filter)) selected - filter else selected + filter
    }
}

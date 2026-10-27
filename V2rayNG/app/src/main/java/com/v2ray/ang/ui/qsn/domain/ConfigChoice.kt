package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/**
 * One eligible configuration offered to the user before a scan starts.
 *
 * Contains no credential: only the fields needed to recognise the config and
 * judge whether it is the right one.
 */
data class ConfigChoice(
    val guid: String,
    val remarks: String,
    val protocol: String,
    val network: String,
    val security: String,
    val port: Int,
    val sniOrHost: String,
    val subscriptionName: String,
) {
    companion object {
        fun from(
            guid: String,
            profile: ProfileItem,
            subscriptionName: String,
        ): ConfigChoice = ConfigChoice(
            guid = guid,
            remarks = profile.remarks.ifBlank { profile.server.orEmpty() },
            protocol = profile.configType.name,
            network = profile.network.orEmpty(),
            security = profile.security.orEmpty(),
            port = profile.serverPort?.toIntOrNull() ?: 0,
            sniOrHost = profile.sni?.takeIf { it.isNotBlank() }
                ?: profile.host?.takeIf { it.isNotBlank() }
                ?: "",
            subscriptionName = subscriptionName,
        )
    }
}

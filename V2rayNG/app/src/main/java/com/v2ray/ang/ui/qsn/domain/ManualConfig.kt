package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/**
 * Rules for configurations the user adds by hand.
 *
 * Eligibility is **not** decided here: `AutoConfigFilter` is the single rule
 * used by the automatic flow, the Configurations screen and the manual import,
 * so the three can never disagree. A previous copy of the rule lived in this
 * object with slightly different semantics (case-insensitive transport and
 * security, no protocol or credential check); it was removed so that a
 * configuration accepted by one path cannot be refused by another.
 */
object ManualConfigRules {

    /**
     * Canonical, credential-bearing identity used only for duplicate detection.
     *
     * It is hashed before it leaves this function, so nothing reversible is
     * ever stored or logged.
     */
    /**
     * Duplicate-detection key for a stored profile.
     *
     * Uses only the fields that define the connection identity, so renaming a
     * profile or re-importing it does not make it look like a new
     * configuration. The credential is folded in so two different keys on the
     * same server are not treated as duplicates.
     */
    fun keyOf(profile: ProfileItem): String = contentKey(
        configType = profile.configType.name,
        server = profile.server,
        port = profile.serverPort?.toIntOrNull() ?: 0,
        network = profile.network,
        security = profile.security,
        sni = profile.sni,
        host = profile.host,
        path = profile.path,
        credential = listOfNotNull(
            profile.password,
            profile.username,
            profile.secretKey,
            profile.publicKey,
        ).joinToString(":"),
    )

    fun contentKey(
        configType: String,
        server: String?,
        port: Int,
        network: String?,
        security: String?,
        sni: String?,
        host: String?,
        path: String?,
        credential: String?,
    ): String {
        val material = listOf(
            configType,
            server.orEmpty(),
            port.toString(),
            network.orEmpty(),
            security.orEmpty(),
            sni.orEmpty(),
            host.orEmpty(),
            path.orEmpty(),
            credential.orEmpty(),
        ).joinToString("|")
        return IpFingerprint.sha256(material)
    }
}

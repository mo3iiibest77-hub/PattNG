package com.v2ray.ang.ui.qsn.backend

/**
 * Clean contract for future backend license / entitlement validation.
 *
 * Keeps future API-based license checks decoupled from scanner/VPN logic.
 * Until a real backend endpoint is integrated, [LocalDefaultLicenseProvider]
 * reports [LicenseStatus.Unconfigured] with zero fake network requests or
 * hardcoded credentials.
 */
interface LicenseProvider {
    suspend fun validate(licenseKey: String? = null): LicenseStatus
}

sealed interface LicenseStatus {
    data object Unconfigured : LicenseStatus
    data class Active(
        val planName: String? = null,
        val expiresAtMillis: Long? = null,
    ) : LicenseStatus
    data object Expired : LicenseStatus
    data class Invalid(val reason: String) : LicenseStatus
    data class Error(val reason: String) : LicenseStatus
}

/** Default local implementation used while direct subscription URLs are active. */
class LocalDefaultLicenseProvider : LicenseProvider {
    override suspend fun validate(licenseKey: String?): LicenseStatus =
        LicenseStatus.Unconfigured
}

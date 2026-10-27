package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.ProfileItem

/**
 * Applies one selected Cloudflare edge to the configurations that may use it.
 *
 * The scope is deliberately narrow: either every *eligible* configuration of a
 * single subscription, or exactly one manually managed configuration. A config
 * from another subscription, or one whose transport cannot go through
 * Cloudflare, is never touched.
 *
 * Only [ProfileItem.qsEdgeIp] is written. Name, protocol, TLS, SNI, Host, Path,
 * `cs` (cipherSuites) and finalMask keep their stored values, and the origin
 * address in [ProfileItem.server] is never overwritten, so the change is
 * reversible and the real server stays known.
 */
object EdgeApplication {

    /** One stored configuration, paired with its GUID. */
    data class Target(val guid: String, val profile: ProfileItem)

    /** Which configurations an edge may be applied to. */
    sealed interface Scope {
        /** Every eligible config of this subscription. */
        data class Subscription(val subscriptionId: String) : Scope

        /** One manually added configuration. */
        data class Single(val guid: String) : Scope
    }

    /**
     * Selects the configurations [scope] covers.
     *
     * @param isEligible the authoritative Cloudflare eligibility rule, injected
     *   so this stays a pure function and the same rule is used everywhere.
     */
    fun targets(
        scope: Scope,
        candidates: List<Target>,
        isEligible: (ProfileItem) -> Boolean,
    ): List<Target> = when (scope) {
        is Scope.Single -> candidates.filter { it.guid == scope.guid && isEligible(it.profile) }
        is Scope.Subscription -> candidates.filter { target ->
            target.profile.subscriptionId == scope.subscriptionId && isEligible(target.profile)
        }
    }

    /**
     * Writes [ip] into every target.
     *
     * @return the GUIDs that actually changed, so the caller persists exactly
     *   those and can report a truthful count.
     */
    fun apply(targets: List<Target>, ip: String): List<String> {
        if (!IpAddress.isIpv4(ip)) return emptyList()
        val changed = ArrayList<String>(targets.size)
        targets.forEach { target ->
            if (target.profile.qsEdgeIp != ip) {
                target.profile.qsEdgeIp = ip
                changed.add(target.guid)
            }
        }
        return changed
    }
}

package com.v2ray.ang.ui.qsn.backend

import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.handler.AngConfigManager

/**
 * Clean abstraction for fetching or syncing user subscriptions.
 *
 * Today [DirectUrlSubscriptionProvider] delegates to the standard direct
 * subscription URL import/refresh flow via [AngConfigManager]. Future backend
 * APIs can implement this interface alongside [LicenseProvider] without
 * touching connection orchestration or UI workflows.
 */
interface SubscriptionProvider {
    suspend fun sync(target: SubscriptionCache): SubscriptionSyncOutcome
}

sealed interface SubscriptionSyncOutcome {
    data class Updated(val configCount: Int) : SubscriptionSyncOutcome
    data class Failed(val reason: String) : SubscriptionSyncOutcome
}

class DirectUrlSubscriptionProvider : SubscriptionProvider {
    override suspend fun sync(target: SubscriptionCache): SubscriptionSyncOutcome {
        return try {
            val result = AngConfigManager.updateConfigViaSub(target)
            if (result.configCount > 0) {
                SubscriptionSyncOutcome.Updated(result.configCount)
            } else {
                SubscriptionSyncOutcome.Failed("Subscription returned 0 configs")
            }
        } catch (e: Exception) {
            SubscriptionSyncOutcome.Failed(e.javaClass.simpleName)
        }
    }
}

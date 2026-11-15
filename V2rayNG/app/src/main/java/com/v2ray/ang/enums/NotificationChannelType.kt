package com.v2ray.ang.enums

/**
 * Enum defining different notification channels.
 * Each channel has a unique channelId, notificationId, and display name.
 */
enum class NotificationChannelType(
    val channelId: String,
    val channelName: String,
    val notificationId: Int
) {
    SUBSCRIPTION_UPDATE(
        channelId = "novapulse_sync_channel",
        channelName = "NovaPulse Sync",
        notificationId = 13
    ),
    CORE_TEST(
        channelId = "novapulse_latency_channel",
        channelName = "NovaPulse Latency Check",
        notificationId = 12
    )
}
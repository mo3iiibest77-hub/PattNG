package com.v2ray.ang.dto

/**
 * A subscription response: the config body plus the optional quota header.
 *
 * The `Subscription-Userinfo` header is only present on servers that publish a
 * traffic allowance, so [userInfo] is null when the server sent none.
 */
data class SubscriptionContent(
    val content: String = "",
    val userInfo: String? = null,
)
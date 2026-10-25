package com.andforce.andclaw.bridge.qqbot

import com.base.services.RemoteChannel
import com.base.services.RemoteIncomingMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val QQBOT_C2C_INTENT: Long = 1L shl 25

internal fun buildQqBotIdentifyPayload(accessToken: String): JsonObject = JsonObject().apply {
    addProperty("op", 2)
    add("d", JsonObject().apply {
        addProperty("token", "QQBot $accessToken")
        addProperty("intents", QQBOT_C2C_INTENT)
        add("shard", JsonArray().apply {
            add(0)
            add(1)
        })
    })
}

internal fun parseQqBotInboundEvent(eventName: String?, event: JsonObject): RemoteIncomingMessage? {
    if (eventName != "C2C_MESSAGE_CREATE") return null

    val messageId = event.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val author = event.getAsJsonObject("author") ?: return null
    val openId = author.get("user_openid")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    if (messageId.isBlank() || openId.isBlank()) return null

    val displayName = author.get("username")?.takeUnless { it.isJsonNull }?.asString

    return RemoteIncomingMessage(
        channel = RemoteChannel.QQBOT,
        sessionKey = openId,
        userId = openId,
        displayName = displayName,
        messageId = messageId,
        text = event.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        senderId = openId,
        senderDisplayName = displayName,
        receivedAtMs = System.currentTimeMillis()
    )
}
package com.andforce.andclaw.bridge.qqbot

import com.base.services.RemoteChannel
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqBotProtocolTest {

    @Test
    fun identifyPayload_containsBotTokenC2cIntentAndSingleShard() {
        val payload = buildQqBotIdentifyPayload("access-token")

        assertEquals(2, payload.get("op").asInt)
        assertEquals("QQBot access-token", payload.getAsJsonObject("d").get("token").asString)
        assertEquals(QQBOT_C2C_INTENT, payload.getAsJsonObject("d").get("intents").asLong)
        assertEquals("[0,1]", payload.getAsJsonObject("d").get("shard").toString())
    }

    @Test
    fun parseC2cMessage_buildsRemoteSessionMessage() {
        val event = JsonParser.parseString(
            """{"id":"message-1","content":"打开设置","author":{"user_openid":"user-1","username":"测试用户"}}"""
        ).asJsonObject

        val message = parseQqBotInboundEvent("C2C_MESSAGE_CREATE", event)

        assertEquals(RemoteChannel.QQBOT, message?.channel)
        assertEquals("user-1", message?.sessionKey)
        assertEquals("message-1", message?.messageId)
        assertEquals("打开设置", message?.text)
        assertEquals("测试用户", message?.senderDisplayName)
        assertTrue((message?.receivedAtMs ?: 0L) > 0L)
    }

    @Test
    fun parseUnsupportedOrMalformedEvent_returnsNull() {
        val event = JsonParser.parseString("""{"id":"message-1","content":"hi"}""").asJsonObject

        assertNull(parseQqBotInboundEvent("GROUP_AT_MESSAGE_CREATE", event))
        assertNull(parseQqBotInboundEvent("C2C_MESSAGE_CREATE", event))
    }

    @Test
    fun mediaMessage_containsFileInfoCaptionAndPassiveReplyFields() {
        val payload = buildQqBotMediaMessagePayload(
            fileInfo = "opaque-file-info",
            messageId = "message-2",
            sequence = 2,
            caption = "Screenshot"
        )

        assertEquals(7, payload.get("msg_type").asInt)
        assertEquals("opaque-file-info", payload.getAsJsonObject("media").get("file_info").asString)
        assertEquals("Screenshot", payload.get("content").asString)
        assertEquals("message-2", payload.get("msg_id").asString)
        assertEquals(2, payload.get("msg_seq").asInt)
    }
}
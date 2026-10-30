package com.andforce.andclaw.bridge.qqbot

import android.util.Log
import com.base.services.BridgeStatus
import com.base.services.RemoteIncomingMessage
import com.base.services.RemoteSession
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class QqBotBridge(
    private val scope: CoroutineScope,
    private val getAppId: () -> String,
    private val getClientSecret: () -> String,
    private val getAllowedOpenId: () -> String,
    private val onInbound: suspend (RemoteIncomingMessage) -> Unit,
    private val onConnectionStatus: (BridgeStatus, String) -> Unit
) {
    private val api = QqBotApiClient()
    private val gson = Gson()
    private val socketScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
    private val webSocketClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val replySequences = ConcurrentHashMap<String, AtomicInteger>()

    private var webSocket: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var accessToken: String? = null
    private var tokenExpiresAt = 0L
    private var lastSequence: Long? = null
    private var stopped = true

    fun start() {
        stopInternal(setStatus = false)
        stopped = false
        if (getAppId().isBlank() || getClientSecret().isBlank()) {
            onConnectionStatus(BridgeStatus.NOT_CONFIGURED, "请填写 QQ Bot AppID 和 ClientSecret")
            return
        }
        onConnectionStatus(BridgeStatus.DISCONNECTED, "正在验证 AppID/ClientSecret 并连接 QQ Gateway…")
        connect()
    }

    fun stop() {
        stopInternal(setStatus = true)
    }

    private fun stopInternal(setStatus: Boolean) {
        stopped = true
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client stopped")
        webSocket = null
        if (setStatus) onConnectionStatus(BridgeStatus.STOPPED, "已停止")
    }

    private fun connect() {
        if (stopped || connectJob?.isActive == true) return
        connectJob = socketScope.launch {
            val appId = getAppId().trim()
            val secret = getClientSecret().trim()
            try {
                val token = getAccessToken(appId, secret)
                val gateway = api.fetchGatewayUrl(token).getOrThrow()
                val request = Request.Builder().url(gateway).build()
                webSocket = webSocketClient.newWebSocket(request, listener(token))
            } catch (error: Exception) {
                Log.e(TAG, "QQ Bot connect failed: ${error.message}")
                onConnectionStatus(
                    BridgeStatus.DISCONNECTED,
                    error.message ?: "连接 QQ Bot 失败，请检查 AppID、ClientSecret 和网络"
                )
                scheduleReconnect()
            }
        }
    }

    private fun listener(token: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "QQ Bot Gateway socket opened")
            onConnectionStatus(BridgeStatus.DISCONNECTED, "Gateway WebSocket 已连接，等待机器人就绪…")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketScope.launch { handlePayload(webSocket, text, token) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            onConnectionStatus(
                if (stopped) BridgeStatus.STOPPED else BridgeStatus.DISCONNECTED,
                if (stopped) "已停止" else "Gateway 连接关闭 (code=$code${reason.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()})"
            )
            if (!stopped && code != 1000) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            Log.e(TAG, "QQ Bot Gateway failure: ${error.message}")
            heartbeatJob?.cancel()
            val httpStatus = response?.code?.let { "HTTP $it: " }.orEmpty()
            onConnectionStatus(BridgeStatus.DISCONNECTED, "Gateway 连接失败：$httpStatus${error.message ?: "未知网络错误"}")
            if (!stopped) scheduleReconnect()
        }
    }

    private suspend fun handlePayload(webSocket: WebSocket, raw: String, token: String) {
        try {
            val payload = gson.fromJson(raw, JsonObject::class.java)
            val op = payload.get("op")?.asInt ?: return
            when (op) {
                OP_HELLO -> {
                    val interval = payload.getAsJsonObject("d")?.get("heartbeat_interval")?.asLong ?: 45000L
                    startHeartbeat(webSocket, interval)
                    webSocket.send(buildQqBotIdentifyPayload(token).toString())
                }
                OP_DISPATCH -> {
                    lastSequence = payload.get("s")?.takeUnless { it.isJsonNull }?.asLong ?: lastSequence
                    if (payload.get("t")?.asString == "READY") {
                        onConnectionStatus(BridgeStatus.CONNECTED, "QQ Bot 已连接，可以开始使用")
                    } else {
                        val event = payload.getAsJsonObject("d") ?: return
                        val incoming = parseQqBotInboundEvent(payload.get("t")?.asString, event)
                            ?: return
                        replySequences.clear()
                        val allowedOpenId = getAllowedOpenId().trim()
                        if (allowedOpenId.isBlank() || incoming.sessionKey != allowedOpenId) {
                            sendTextInternal(
                                RemoteSession(
                                    channel = com.base.services.RemoteChannel.QQBOT,
                                    sessionKey = incoming.sessionKey,
                                    messageId = incoming.messageId
                                ),
                                "此 QQ 账号未获授权使用该设备。请在 Andclaw 的 QQ Bot 设置中将此 OpenID 加入白名单：${incoming.sessionKey}"
                            )
                        } else {
                            onInbound(incoming)
                        }
                    }
                }
                OP_RECONNECT -> webSocket.close(4000, "Gateway requested reconnect")
                OP_INVALID_SESSION -> webSocket.close(4001, "Invalid session")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to handle QQ Bot Gateway payload: ${error.message}")
        }
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = socketScope.launch {
            while (isActive && !stopped) {
                delay(intervalMs)
                val payload = JsonObject().apply {
                    addProperty("op", OP_HEARTBEAT)
                    lastSequence?.let { addProperty("d", it) } ?: add("d", null)
                }
                if (!webSocket.send(payload.toString())) break
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = socketScope.launch {
            delay(RECONNECT_DELAY_MS)
            connect()
        }
    }

    private suspend fun getAccessToken(appId: String, secret: String): String {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS }?.let { return it }
        val token = api.fetchAccessToken(appId, secret).getOrThrow()
        accessToken = token.value
        tokenExpiresAt = now + token.expiresInSeconds * 1000L
        return token.value
    }

    private suspend fun sendTextInternal(session: RemoteSession, text: String) {
        val userOpenId = session.sessionKey
        if (userOpenId.isBlank()) return
        val token = getAccessToken(getAppId().trim(), getClientSecret().trim())
        val reply = nextReplyReference(session)
        api.sendText(token, userOpenId, reply.messageId, reply.sequence, text).getOrThrow()
    }

    suspend fun sendText(session: RemoteSession, text: String) {
        runCatching { sendTextInternal(session, text) }
            .onFailure { Log.e(TAG, "QQ Bot sendText failed: ${it.message}") }
    }

    suspend fun sendPhoto(session: RemoteSession, photoBytes: ByteArray, caption: String?, fileName: String) {
        sendMediaOrFallback(session, photoBytes, fileName, FILE_TYPE_IMAGE, caption, "图片")
    }

    suspend fun sendVideo(session: RemoteSession, videoBytes: ByteArray, caption: String?, fileName: String) {
        sendMediaOrFallback(session, videoBytes, fileName, FILE_TYPE_VIDEO, caption, "视频")
    }

    suspend fun sendAudio(session: RemoteSession, audioBytes: ByteArray, caption: String?, fileName: String) {
        sendMediaOrFallback(session, audioBytes, fileName, FILE_TYPE_FILE, caption, "音频文件")
    }

    private suspend fun sendMediaOrFallback(
        session: RemoteSession,
        fileBytes: ByteArray,
        fileName: String,
        fileType: Int,
        caption: String?,
        mediaLabel: String
    ) {
        try {
            val userOpenId = session.sessionKey
            require(userOpenId.isNotBlank()) { "QQ 用户 OpenID 为空" }
            val token = getAccessToken(getAppId().trim(), getClientSecret().trim())
            val fileInfo = api.uploadMedia(token, userOpenId, fileBytes, fileName, fileType).getOrThrow()
            val reply = nextReplyReference(session)
            api.sendMedia(
                token,
                userOpenId,
                buildQqBotMediaMessagePayload(fileInfo, reply.messageId, reply.sequence, caption)
            ).getOrThrow()
        } catch (error: Exception) {
            Log.e(TAG, "QQ Bot $mediaLabel send failed: ${error.message}")
            runCatching {
                sendTextInternal(session, "${mediaLabel}发送失败，文件仍保存在设备本地：$fileName (${error.message ?: "未知错误"})")
            }.onFailure { Log.e(TAG, "QQ Bot media failure notice failed: ${it.message}") }
        }
    }

    private fun nextReplyReference(session: RemoteSession): ReplyReference {
        val messageId = session.messageId
        val ageMs = System.currentTimeMillis() - session.lastIncomingAt
        val tracker = messageId?.takeIf { ageMs in 0..PASSIVE_REPLY_WINDOW_MS }
            ?.let { replySequences.getOrPut(it) { AtomicInteger(0) } }
        val sequence = tracker?.incrementAndGet() ?: 0
        val passiveMessageId = messageId?.takeIf { sequence in 1..MAX_PASSIVE_REPLIES }
        return ReplyReference(passiveMessageId, if (passiveMessageId == null) 1 else sequence)
    }

    suspend fun sendTyping(session: RemoteSession) = Unit

    companion object {
        private const val TAG = "QqBotBridge"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val RECONNECT_DELAY_MS = 5000L
        private const val TOKEN_REFRESH_MARGIN_MS = 60000L
        private const val PASSIVE_REPLY_WINDOW_MS = 4 * 60 * 1000L
        private const val MAX_PASSIVE_REPLIES = 4
        private const val FILE_TYPE_IMAGE = 1
        private const val FILE_TYPE_VIDEO = 2
        private const val FILE_TYPE_FILE = 4
    }

    private data class ReplyReference(val messageId: String?, val sequence: Int)
}
package com.andforce.andclaw.bridge.qqbot

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class QqBotApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    suspend fun fetchAccessToken(appId: String, clientSecret: String): Result<Token> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("appId", appId)
                addProperty("clientSecret", clientSecret)
            }
            val request = Request.Builder()
                .url("$API_BASE/app/getAppAccessToken")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException("获取 QQ access_token 失败: HTTP ${response.code}")
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val errorCode = json.get("code")?.asInt
                if (errorCode != null && errorCode != 0) {
                    throw ApiException("QQ access_token 错误: $errorCode ${json.get("message")?.asString.orEmpty()}")
                }
                Token(
                    value = json.get("access_token")?.asString
                        ?: throw ApiException("QQ access_token 响应缺少 access_token"),
                    expiresInSeconds = json.get("expires_in")?.asLong ?: 7200L
                )
            }
        }
    }

    suspend fun fetchGatewayUrl(accessToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$API_BASE/gateway")
                .header("Authorization", "QQBot $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException("获取 QQ Gateway 失败: HTTP ${response.code}")
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val errorCode = json.get("code")?.asInt
                if (errorCode != null && errorCode != 0) {
                    throw ApiException("QQ Gateway 错误: $errorCode ${json.get("message")?.asString.orEmpty()}")
                }
                json.get("url")?.asString
                    ?: throw ApiException("QQ Gateway 响应缺少 url")
            }
        }
    }

    suspend fun sendText(
        accessToken: String,
        userOpenId: String,
        messageId: String?,
        sequence: Int,
        text: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("msg_type", 0)
                addProperty("content", text)
                if (!messageId.isNullOrBlank()) {
                    addProperty("msg_id", messageId)
                    addProperty("msg_seq", sequence)
                }
            }
            val request = Request.Builder()
                .url("$API_BASE/v2/users/$userOpenId/messages")
                .header("Authorization", "QQBot $accessToken")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException("发送 QQ 消息失败: HTTP ${response.code}")
                val json = responseBody.takeIf(String::isNotBlank)?.let {
                    gson.fromJson(it, JsonObject::class.java)
                }
                val errorCode = json?.get("code")?.asInt
                if (errorCode != null && errorCode != 0) {
                    throw ApiException("发送 QQ 消息错误: $errorCode ${json.get("message")?.asString.orEmpty()}")
                }
            }
        }
    }

    data class Token(val value: String, val expiresInSeconds: Long)

    private class ApiException(message: String) : Exception(message)

    companion object {
        private const val API_BASE = "https://api.bot.qq.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
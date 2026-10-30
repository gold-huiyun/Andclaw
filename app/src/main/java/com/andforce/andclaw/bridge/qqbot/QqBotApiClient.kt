package com.andforce.andclaw.bridge.qqbot

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
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

    suspend fun uploadMedia(
        accessToken: String,
        userOpenId: String,
        fileBytes: ByteArray,
        fileName: String,
        fileType: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(fileBytes.isNotEmpty()) { "上传文件为空" }
            require(fileBytes.size <= MAX_FILE_SIZE) { "QQ 单文件上限为 200 MB" }
            val fileInfo = if (fileBytes.size <= DIRECT_UPLOAD_LIMIT) {
                uploadSmallMedia(accessToken, userOpenId, fileBytes, fileName, fileType)
            } else {
                uploadChunkedMedia(accessToken, userOpenId, fileBytes, fileName, fileType)
            }
            fileInfo
        }
    }

    suspend fun sendMedia(
        accessToken: String,
        userOpenId: String,
        payload: JsonObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$API_BASE/v2/users/$userOpenId/messages")
                .header("Authorization", "QQBot $accessToken")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            executeApiRequest(request, "发送 QQ 富媒体")
            Unit
        }
    }

    private fun uploadSmallMedia(
        accessToken: String,
        userOpenId: String,
        fileBytes: ByteArray,
        fileName: String,
        fileType: Int
    ): String {
        val body = JsonObject().apply {
            addProperty("file_type", fileType)
            addProperty("file_name", fileName)
            addProperty("file_data", Base64.encodeToString(fileBytes, Base64.NO_WRAP))
            addProperty("srv_send_msg", false)
        }
        val request = apiRequest("/v2/users/$userOpenId/files", accessToken, body)
        return executeApiRequest(request, "上传 QQ 媒体").get("file_info")?.asString
            ?: throw ApiException("QQ 媒体上传响应缺少 file_info")
    }

    private fun uploadChunkedMedia(
        accessToken: String,
        userOpenId: String,
        fileBytes: ByteArray,
        fileName: String,
        fileType: Int
    ): String {
        val fileMd5 = digest("MD5", fileBytes)
        val prepareBody = JsonObject().apply {
            addProperty("file_type", fileType)
            addProperty("file_name", fileName)
            addProperty("file_size", fileBytes.size.toString())
            addProperty("md5", fileMd5)
            addProperty("sha1", digest("SHA-1", fileBytes))
            val md5PrefixSize = 10_002_432
            addProperty("md5_10m", if (fileBytes.size > md5PrefixSize) {
                digest("MD5", fileBytes.copyOfRange(0, md5PrefixSize))
            } else {
                fileMd5
            })
        }
        val prepared = executeApiRequest(
            apiRequest("/v2/users/$userOpenId/upload_prepare", accessToken, prepareBody),
            "准备 QQ 分片上传"
        )
        val uploadId = prepared.get("upload_id")?.asString
            ?: throw ApiException("QQ 分片上传响应缺少 upload_id")
        val blockSize = prepared.get("block_size")?.asLong?.toInt()
            ?: throw ApiException("QQ 分片上传响应缺少 block_size")
        require(blockSize > 0) { "QQ 分片大小无效" }
        val parts = prepared.getAsJsonArray("parts")
            ?: throw ApiException("QQ 分片上传响应缺少 parts")

        parts.forEachIndexed { partPosition, partElement ->
            val part = partElement.asJsonObject
            val partIndex = part.get("index")?.asInt
                ?: throw ApiException("QQ 分片信息缺少 index")
            val uploadUrl = part.get("presigned_url")?.asString
                ?: throw ApiException("QQ 分片信息缺少 presigned_url")
            val offset = partPosition * blockSize
            val end = minOf(offset + blockSize, fileBytes.size)
            require(offset < end) { "QQ 分片范围无效: index=$partIndex" }
            val partBytes = fileBytes.copyOfRange(offset, end)
            val putRequest = Request.Builder()
                .url(uploadUrl)
                .put(partBytes.toRequestBody(null))
                .build()
            client.newCall(putRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException("上传 QQ 分片 $partIndex 失败: HTTP ${response.code}")
                }
            }

            val finishBody = JsonObject().apply {
                addProperty("upload_id", uploadId)
                addProperty("part_index", partIndex)
                addProperty("block_size", partBytes.size.toString())
                addProperty("md5", digest("MD5", partBytes))
            }
            executeApiRequest(
                apiRequest("/v2/users/$userOpenId/upload_part_finish", accessToken, finishBody),
                "确认 QQ 分片 $partIndex"
            )
        }

        val completeBody = JsonObject().apply { addProperty("upload_id", uploadId) }
        return executeApiRequest(
            apiRequest("/v2/users/$userOpenId/files", accessToken, completeBody),
            "合并 QQ 媒体分片"
        ).get("file_info")?.asString ?: throw ApiException("QQ 分片上传完成响应缺少 file_info")
    }

    private fun apiRequest(path: String, accessToken: String, body: JsonObject): Request =
        Request.Builder()
            .url("$API_BASE$path")
            .header("Authorization", "QQBot $accessToken")
            .post(body.toString().toRequestBody(JSON))
            .build()

    private fun executeApiRequest(request: Request, operation: String): JsonObject {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("$operation 失败: HTTP ${response.code} $responseBody")
            val json = responseBody.takeIf(String::isNotBlank)?.let {
                gson.fromJson(it, JsonObject::class.java)
            } ?: JsonObject()
            val errorCode = json.get("code")?.asInt
            if (errorCode != null && errorCode != 0) {
                throw ApiException("$operation 错误: $errorCode ${json.get("message")?.asString.orEmpty()}")
            }
            return json
        }
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    data class Token(val value: String, val expiresInSeconds: Long)

    private class ApiException(message: String) : Exception(message)

    companion object {
        private const val API_BASE = "https://api.bot.qq.com"
        private const val DIRECT_UPLOAD_LIMIT = 5 * 1024 * 1024
        private const val MAX_FILE_SIZE = 200 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
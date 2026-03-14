package com.proyectoj.assistant.data.network

import android.content.Context
import android.net.Uri
import com.proyectoj.assistant.data.network.dto.BuildLogsChunk
import com.proyectoj.assistant.data.network.dto.BuildSummary
import com.proyectoj.assistant.data.network.dto.LatestRollbackInfo
import com.proyectoj.assistant.data.network.dto.RollbackStatus
import com.proyectoj.assistant.data.network.parser.AssistantApiParser
import com.proyectoj.assistant.network.UpdateInfo
import com.proyectoj.assistant.network.UpdateInfoParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .writeTimeout(700, TimeUnit.MILLISECONDS)
        .build()
    private val serverDiscovery = ServerDiscovery(context, client, discoveryClient)

    companion object {
        private const val CHAT_PATH = "/chat"
        private const val UPDATE_LATEST_PATH = "/mobile/update/latest"
        private const val ACTIVE_BUILDS_PATH = "/builds/active"
        private const val ROLLBACK_LATEST_PATH = "/rollback/latest"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun postMessage(message: String): Result<String> {
        return postMessageInternal(message, allowRetryWithRediscovery = true)
    }

    private fun postMessageInternal(message: String, allowRetryWithRediscovery: Boolean): Result<String> {
        val chatUrl = resolveBaseUrl()?.let { "$it$CHAT_PATH" }
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("chat requests")))

        return try {
            val requestJson = JSONObject().put("message", message).toString()
            val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(chatUrl)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty response body."))
                }

                val responseText = JSONObject(body).optString("response", "")
                if (responseText.isBlank()) {
                    return Result.failure(IOException("Response JSON does not contain a valid 'response' field."))
                }

                Result.success(responseText)
            }
        } catch (ex: IOException) {
            if (allowRetryWithRediscovery) {
                serverDiscovery.clearResolvedServer()
                return postMessageInternal(message, allowRetryWithRediscovery = false)
            }
            Result.failure(ex)
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from server.", ex))
        }
    }

    fun fetchLatestUpdateInfo(): Result<UpdateInfo> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("update checks")))

        val request = Request.Builder()
            .url("$baseUrl$UPDATE_LATEST_PATH")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty OTA response body."))
                }
                UpdateInfoParser.parseLatestResponse(body)
            }
        } catch (ex: IOException) {
            Result.failure(ex)
        }
    }

    fun fetchActiveBuilds(): Result<List<BuildSummary>> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("build logs")))
        val request = Request.Builder()
            .url("$baseUrl$ACTIVE_BUILDS_PATH")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.success(emptyList())
                }
                AssistantApiParser.parseBuildSummaries(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch active builds.", ex))
        }
    }

    fun fetchBuildLogs(branch: String, fromSeq: Int): Result<BuildLogsChunk> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("build logs")))
        val encodedBranch = Uri.encode(branch)
        val request = Request.Builder()
            .url("$baseUrl/build/$encodedBranch/logs?from_seq=$fromSeq")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty build logs body."))
                }
                AssistantApiParser.parseBuildLogsChunk(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch build logs.", ex))
        }
    }

    fun fetchLatestRollbackInfo(): Result<LatestRollbackInfo> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("rollback info")))
        val request = Request.Builder()
            .url("$baseUrl$ROLLBACK_LATEST_PATH")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty rollback response body."))
                }
                AssistantApiParser.parseLatestRollbackInfo(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch rollback info.", ex))
        }
    }

    fun startLatestRollback(expectedJobBranch: String, expectedUpdatedAt: String): Result<RollbackStatus> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("rollback trigger")))
        val payload = JSONObject()
            .put("expected_job_branch", expectedJobBranch)
            .put("expected_updated_at", expectedUpdatedAt)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl$ROLLBACK_LATEST_PATH")
            .post(payload)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(
                        IOException(AssistantApiParser.parseHttpError(response.code, response.message, body))
                    )
                }
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty rollback start body."))
                }
                AssistantApiParser.parseRollbackStatus(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not start rollback.", ex))
        }
    }

    fun fetchRollbackStatus(rollbackId: String): Result<RollbackStatus> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException(serverDiscovery.failureMessage("rollback status")))
        val request = Request.Builder()
            .url("$baseUrl/rollback/${Uri.encode(rollbackId)}")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty rollback status body."))
                }
                AssistantApiParser.parseRollbackStatus(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch rollback status.", ex))
        }
    }

    private fun resolveBaseUrl(): String? {
        return serverDiscovery.resolveBaseUrl()
    }
}

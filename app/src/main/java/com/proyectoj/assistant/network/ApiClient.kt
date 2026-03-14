package com.proyectoj.assistant.network

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import com.proyectoj.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class BuildSummary(
    val branch: String,
    val status: String,
    val message: String,
    val updatedAt: String,
    val logCount: Int
)

data class BuildLogsChunk(
    val branch: String,
    val status: String,
    val nextSeq: Int,
    val logs: List<String>
)

data class RollbackRepo(
    val repoName: String,
    val status: String,
    val message: String,
    val externalSideEffects: List<String>
)

data class RollbackValidation(
    val scope: String,
    val command: String,
    val status: String,
    val message: String
)

data class RollbackStatus(
    val rollbackId: String,
    val targetJobBranch: String,
    val status: String,
    val message: String,
    val updatedAt: String,
    val finishedAt: String,
    val repos: List<RollbackRepo>,
    val validation: List<RollbackValidation>
)

data class LatestRollbackJob(
    val branch: String,
    val title: String,
    val descriptionPreview: String,
    val updatedAt: String,
    val hasCommits: Boolean,
    val rollbackEligible: Boolean,
    val rollbackBlockReason: String,
    val externalSideEffects: List<String>,
    val repos: List<String>
)

data class LatestRollbackInfo(
    val availabilityStatus: String,
    val canRevert: Boolean,
    val reason: String,
    val job: LatestRollbackJob?,
    val rollback: RollbackStatus?
)

class ApiClient(private val context: Context) {
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

    @Volatile
    private var resolvedBaseUrl: String? = null

    companion object {
        private const val TAG = "ApiClient"
        private const val PREFS_NAME = "assistant_network"
        private const val PREF_KEY_BASE_URL = "server_base_url"
        private const val DEFAULT_PORT = 8000
        private const val CHAT_PATH = "/chat"
        private const val HEALTH_PATH = "/health"
        private const val UPDATE_LATEST_PATH = "/mobile/update/latest"
        private const val ACTIVE_BUILDS_PATH = "/builds/active"
        private const val ROLLBACK_LATEST_PATH = "/rollback/latest"
        private const val EMULATOR_BASE_URL = "http://10.0.2.2:8000"
        private const val FALLBACK_BASE_URL = "http://192.168.1.2:8000"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun postMessage(message: String): Result<String> {
        return postMessageInternal(message, allowRetryWithRediscovery = true)
    }

    private fun postMessageInternal(message: String, allowRetryWithRediscovery: Boolean): Result<String> {
        val chatUrl = resolveChatUrl()
            ?: return Result.failure(
                IOException("Could not discover assistant server on local network.")
            )

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
                Log.w(TAG, "Request failed against $chatUrl. Clearing cache and retrying discovery.", ex)
                clearResolvedServer()
                return postMessageInternal(message, allowRetryWithRediscovery = false)
            }
            Result.failure(ex)
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from server.", ex))
        }
    }

    private fun resolveChatUrl(): String? {
        val baseUrl = resolveBaseUrl()
        return baseUrl?.let { "$it$CHAT_PATH" }
    }

    fun fetchLatestUpdateInfo(): Result<UpdateInfo> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException("Could not discover assistant server for update checks."))

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
            ?: return Result.failure(IOException("Could not discover assistant server for build logs."))
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
                parseBuildSummaries(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch active builds.", ex))
        }
    }

    fun fetchBuildLogs(branch: String, fromSeq: Int): Result<BuildLogsChunk> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException("Could not discover assistant server for build logs."))
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
                parseBuildLogsChunk(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch build logs.", ex))
        }
    }

    fun fetchLatestRollbackInfo(): Result<LatestRollbackInfo> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException("Could not discover assistant server for rollback info."))
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
                parseLatestRollbackInfo(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch rollback info.", ex))
        }
    }

    fun startLatestRollback(expectedJobBranch: String, expectedUpdatedAt: String): Result<RollbackStatus> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException("Could not discover assistant server for rollback trigger."))
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
                    return Result.failure(IOException(parseHttpError(response.code, response.message, body)))
                }
                if (body.isBlank()) {
                    return Result.failure(IOException("Server returned an empty rollback start body."))
                }
                parseRollbackStatus(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not start rollback.", ex))
        }
    }

    fun fetchRollbackStatus(rollbackId: String): Result<RollbackStatus> {
        val baseUrl = resolveBaseUrl()
            ?: return Result.failure(IOException("Could not discover assistant server for rollback status."))
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
                parseRollbackStatus(body)
            }
        } catch (ex: Exception) {
            Result.failure(IOException("Could not fetch rollback status.", ex))
        }
    }

    private fun resolveBaseUrl(): String? {
        return resolvedBaseUrl ?: discoverServerBaseUrl()
    }

    private fun discoverServerBaseUrl(): String? {
        val publicCandidate = normalizePublicBaseUrl(BuildConfig.CLOUDFLARE_PUBLIC_BASE_URL)
        if (!publicCandidate.isNullOrBlank() && isServerHealthy(publicCandidate, useFastClient = false)) {
            persistResolvedServer(publicCandidate)
            return publicCandidate
        }

        val cached = getPersistedBaseUrl()
        if (!cached.isNullOrBlank() && isServerHealthy(cached, useFastClient = true)) {
            resolvedBaseUrl = cached
            return cached
        }

        val directCandidates = linkedSetOf<String>()
        directCandidates.add(EMULATOR_BASE_URL)
        directCandidates.add(FALLBACK_BASE_URL)
        subnetPrefix()?.let { prefix ->
            listOf(2, 10, 11, 20, 50, 100, 101, 110, 120, 150, 200)
                .forEach { host -> directCandidates.add("http://$prefix.$host:$DEFAULT_PORT") }
        }

        for (candidate in directCandidates) {
            if (isServerHealthy(candidate, useFastClient = true)) {
                persistResolvedServer(candidate)
                return candidate
            }
        }

        val discoveredInSubnet = discoverInSubnet()
        if (discoveredInSubnet != null) {
            persistResolvedServer(discoveredInSubnet)
            return discoveredInSubnet
        }

        Log.e(TAG, "Server discovery failed: no healthy /health endpoint found.")
        return null
    }

    private fun discoverInSubnet(): String? {
        val prefix = subnetPrefix() ?: return null
        val found = AtomicReference<String?>(null)
        val executor = Executors.newFixedThreadPool(24)
        try {
            val tasks = (1..254).map { host ->
                Callable<String?> {
                    if (found.get() != null) {
                        return@Callable null
                    }
                    val candidate = "http://$prefix.$host:$DEFAULT_PORT"
                    if (isServerHealthy(candidate, useFastClient = true)) {
                        found.compareAndSet(null, candidate)
                        return@Callable candidate
                    }
                    null
                }
            }
            executor.invokeAll(tasks, 8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            executor.shutdownNow()
        }

        val result = found.get()
        if (result != null) {
            Log.i(TAG, "Discovered server in subnet: $result")
        }
        return result
    }

    private fun isServerHealthy(baseUrl: String, useFastClient: Boolean): Boolean {
        val request = Request.Builder()
            .url("$baseUrl$HEALTH_PATH")
            .get()
            .build()

        return try {
            val activeClient = if (useFastClient) discoveryClient else client
            activeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                val body = response.body?.string().orEmpty()
                JSONObject(body).optString("status") == "ok"
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizePublicBaseUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return null
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun subnetPrefix(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val ipAddress = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipAddress == 0) {
                return null
            }
            val ip = intToIpv4(ipAddress)
            val parts = ip.split(".")
            if (parts.size != 4) {
                return null
            }
            "${parts[0]}.${parts[1]}.${parts[2]}"
        } catch (_: Exception) {
            null
        }
    }

    private fun intToIpv4(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    private fun persistResolvedServer(baseUrl: String) {
        resolvedBaseUrl = baseUrl
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_BASE_URL, baseUrl)
            .apply()
        Log.i(TAG, "Using assistant server: $baseUrl")
    }

    private fun getPersistedBaseUrl(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_BASE_URL, null)
    }

    private fun clearResolvedServer() {
        resolvedBaseUrl = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_BASE_URL)
            .apply()
    }

    private fun parseBuildSummaries(rawBody: String): Result<List<BuildSummary>> {
        return try {
            val root = JSONObject(rawBody)
            val buildsArray = root.optJSONArray("builds")
            val summaries = mutableListOf<BuildSummary>()
            if (buildsArray != null) {
                for (index in 0 until buildsArray.length()) {
                    val item = buildsArray.optJSONObject(index) ?: continue
                    summaries.add(
                        BuildSummary(
                            branch = item.optString("branch", ""),
                            status = item.optString("status", ""),
                            message = item.optString("message", ""),
                            updatedAt = item.optString("updated_at", ""),
                            logCount = item.optInt("log_count", 0)
                        )
                    )
                }
            }
            Result.success(summaries)
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from build list endpoint.", ex))
        }
    }

    private fun parseBuildLogsChunk(rawBody: String): Result<BuildLogsChunk> {
        return try {
            val root = JSONObject(rawBody)
            val logsArray = root.optJSONArray("logs")
            val logs = mutableListOf<String>()
            if (logsArray != null) {
                for (index in 0 until logsArray.length()) {
                    logs.add(logsArray.optString(index, ""))
                }
            }
            Result.success(
                BuildLogsChunk(
                    branch = root.optString("branch", ""),
                    status = root.optString("status", ""),
                    nextSeq = root.optInt("next_seq", 0),
                    logs = logs.filter { it.isNotBlank() }
                )
            )
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from build logs endpoint.", ex))
        }
    }

    private fun parseLatestRollbackInfo(rawBody: String): Result<LatestRollbackInfo> {
        return try {
            val root = JSONObject(rawBody)
            val jobObject = root.optJSONObject("job")
            val rollbackObject = root.optJSONObject("rollback")
            val job = jobObject?.let { item ->
                val repos = mutableListOf<String>()
                val repoArray = item.optJSONArray("repos")
                val sideEffects = mutableListOf<String>()
                val sideEffectsArray = item.optJSONArray("external_side_effects")
                if (repoArray != null) {
                    for (index in 0 until repoArray.length()) {
                        val repo = repoArray.optJSONObject(index) ?: continue
                        repos.add(repo.optString("repo_name", ""))
                    }
                }
                if (sideEffectsArray != null) {
                    for (index in 0 until sideEffectsArray.length()) {
                        sideEffects.add(sideEffectsArray.optString(index, ""))
                    }
                }
                LatestRollbackJob(
                    branch = item.optString("branch", ""),
                    title = item.optString("title", ""),
                    descriptionPreview = item.optString("description_preview", ""),
                    updatedAt = item.optString("completed_at", ""),
                    hasCommits = item.optBoolean("has_commits", false),
                    rollbackEligible = item.optBoolean("rollback_eligible", false),
                    rollbackBlockReason = item.optString("rollback_block_reason", ""),
                    externalSideEffects = sideEffects.filter { it.isNotBlank() },
                    repos = repos.filter { it.isNotBlank() }
                )
            }
            Result.success(
                LatestRollbackInfo(
                    availabilityStatus = root.optString("availability_status", ""),
                    canRevert = root.optBoolean("can_revert", false),
                    reason = root.optString("reason", ""),
                    job = job,
                    rollback = rollbackObject?.let { parseRollbackStatusObject(it) }
                )
            )
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from rollback latest endpoint.", ex))
        }
    }

    private fun parseRollbackStatus(rawBody: String): Result<RollbackStatus> {
        return try {
            Result.success(parseRollbackStatusObject(JSONObject(rawBody)))
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from rollback status endpoint.", ex))
        }
    }

    private fun parseRollbackStatusObject(root: JSONObject): RollbackStatus {
        val repos = mutableListOf<RollbackRepo>()
        val validations = mutableListOf<RollbackValidation>()
        val reposArray = root.optJSONArray("repos")
        val validationArray = root.optJSONArray("validation")
        if (reposArray != null) {
            for (index in 0 until reposArray.length()) {
                val item = reposArray.optJSONObject(index) ?: continue
                val sideEffects = mutableListOf<String>()
                val sideEffectsArray = item.optJSONArray("external_side_effects")
                if (sideEffectsArray != null) {
                    for (sideIndex in 0 until sideEffectsArray.length()) {
                        sideEffects.add(sideEffectsArray.optString(sideIndex, ""))
                    }
                }
                repos.add(
                    RollbackRepo(
                        repoName = item.optString("repo_name", ""),
                        status = item.optString("status", ""),
                        message = item.optString("message", ""),
                        externalSideEffects = sideEffects.filter { it.isNotBlank() }
                    )
                )
            }
        }
        if (validationArray != null) {
            for (index in 0 until validationArray.length()) {
                val item = validationArray.optJSONObject(index) ?: continue
                validations.add(
                    RollbackValidation(
                        scope = item.optString("scope", ""),
                        command = item.optString("command", ""),
                        status = item.optString("status", ""),
                        message = item.optString("message", "")
                    )
                )
            }
        }
        return RollbackStatus(
            rollbackId = root.optString("rollback_id", ""),
            targetJobBranch = root.optString("target_job_branch", ""),
            status = root.optString("status", ""),
            message = root.optString("message", ""),
            updatedAt = root.optString("updated_at", ""),
            finishedAt = root.optString("finished_at", ""),
            repos = repos,
            validation = validations
        )
    }

    private fun parseHttpError(code: Int, message: String, body: String): String {
        if (body.isBlank()) {
            return "HTTP $code: $message"
        }
        return try {
            val root = JSONObject(body)
            val detail = root.optString("detail", "").ifBlank { root.optString("message", "") }
            if (detail.isBlank()) "HTTP $code: $message" else detail
        } catch (_: JSONException) {
            "HTTP $code: $message"
        }
    }
}

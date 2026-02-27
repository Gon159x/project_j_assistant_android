package com.proyectoj.assistant.network

import android.content.Context
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
}

package com.proyectoj.assistant.data.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.proyectoj.assistant.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ServerDiscoverySupport {
    fun normalizePublicBaseUrl(rawUrl: String): String? {
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

    fun buildDirectCandidates(
        prefix: String?,
        defaultPort: Int,
        emulatorBaseUrl: String,
        fallbackBaseUrl: String
    ): LinkedHashSet<String> {
        val directCandidates = linkedSetOf<String>()
        directCandidates.add(emulatorBaseUrl)
        directCandidates.add(fallbackBaseUrl)
        prefix?.let {
            listOf(2, 10, 11, 20, 50, 100, 101, 110, 120, 150, 200)
                .forEach { host -> directCandidates.add("http://$it.$host:$defaultPort") }
        }
        return directCandidates
    }

    fun intToIpv4(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}

class ServerDiscovery(
    private val context: Context,
    private val client: OkHttpClient,
    private val discoveryClient: OkHttpClient
) {
    @Volatile
    private var resolvedBaseUrl: String? = null

    fun resolveBaseUrl(): String? {
        return resolvedBaseUrl ?: discoverServerBaseUrl()
    }

    fun clearResolvedServer() {
        resolvedBaseUrl = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_BASE_URL)
            .apply()
    }

    private fun discoverServerBaseUrl(): String? {
        val publicCandidate = ServerDiscoverySupport.normalizePublicBaseUrl(BuildConfig.CLOUDFLARE_PUBLIC_BASE_URL)
        if (!publicCandidate.isNullOrBlank() && isServerHealthy(publicCandidate, useFastClient = false)) {
            persistResolvedServer(publicCandidate)
            return publicCandidate
        }

        val cached = getPersistedBaseUrl()
        if (!cached.isNullOrBlank() && isServerHealthy(cached, useFastClient = true)) {
            resolvedBaseUrl = cached
            return cached
        }

        val directCandidates = ServerDiscoverySupport.buildDirectCandidates(
            prefix = subnetPrefix(),
            defaultPort = DEFAULT_PORT,
            emulatorBaseUrl = EMULATOR_BASE_URL,
            fallbackBaseUrl = FALLBACK_BASE_URL
        )
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

    private fun subnetPrefix(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val ipAddress = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipAddress == 0) {
                return null
            }
            val ip = ServerDiscoverySupport.intToIpv4(ipAddress)
            val parts = ip.split(".")
            if (parts.size != 4) {
                return null
            }
            "${parts[0]}.${parts[1]}.${parts[2]}"
        } catch (_: Exception) {
            null
        }
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

    private companion object {
        private const val TAG = "ApiClient"
        private const val PREFS_NAME = "assistant_network"
        private const val PREF_KEY_BASE_URL = "server_base_url"
        private const val DEFAULT_PORT = 8000
        private const val HEALTH_PATH = "/health"
        private const val EMULATOR_BASE_URL = "http://10.0.2.2:8000"
        private const val FALLBACK_BASE_URL = "http://192.168.1.2:8000"
    }
}

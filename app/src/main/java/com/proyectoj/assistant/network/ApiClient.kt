package com.proyectoj.assistant.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        // 10.0.2.2 points to host localhost only when running on Android emulator.
        // On a physical device, replace this with your computer LAN IP, e.g. http://192.168.1.50:8000/chat
        const val CHAT_URL = "http://192.168.1.2:8000/chat"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun postMessage(message: String): Result<String> {
        return try {
            val requestJson = JSONObject().put("message", message).toString()
            val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(CHAT_URL)
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
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from server.", ex))
        } catch (ex: IOException) {
            Result.failure(ex)
        }
    }
}

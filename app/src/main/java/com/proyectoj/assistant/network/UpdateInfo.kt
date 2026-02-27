package com.proyectoj.assistant.network

import org.json.JSONException
import org.json.JSONObject

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val publishedAt: String,
    val sha256: String
)

enum class UpdateAction {
    NO_UPDATE,
    DOWNLOAD_UPDATE
}

object UpdateInfoParser {
    fun parseLatestResponse(rawBody: String): Result<UpdateInfo> {
        return try {
            val body = JSONObject(rawBody)
            val versionCode = body.optInt("version_code", 0)
            val versionName = body.optString("version_name", "")
            val apkUrl = body.optString("apk_url", "")
            val releaseNotes = body.optString("release_notes", "")
            val publishedAt = body.optString("published_at", "")
            val sha256 = body.optString("sha256", "")

            if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) {
                return Result.failure(IllegalArgumentException("Invalid OTA payload: missing required fields."))
            }

            Result.success(
                UpdateInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    apkUrl = apkUrl,
                    releaseNotes = releaseNotes,
                    publishedAt = publishedAt,
                    sha256 = sha256
                )
            )
        } catch (ex: JSONException) {
            Result.failure(IllegalArgumentException("Invalid OTA payload JSON.", ex))
        }
    }

    fun decideUpdateAction(currentVersionCode: Int, latest: UpdateInfo?): UpdateAction {
        if (latest == null) {
            return UpdateAction.NO_UPDATE
        }
        return if (latest.versionCode > currentVersionCode) {
            UpdateAction.DOWNLOAD_UPDATE
        } else {
            UpdateAction.NO_UPDATE
        }
    }
}

package com.proyectoj.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoParserTest {
    @Test
    fun parseLatestResponse_withValidPayload_returnsUpdateInfo() {
        val payload = """
            {
              "ok": true,
              "platform": "android",
              "version_code": 8,
              "version_name": "1.8.0",
              "apk_url": "https://example.com/app.apk",
              "release_notes": "Fixes",
              "published_at": "2026-02-27T00:00:00+00:00",
              "sha256": "abc"
            }
        """.trimIndent()

        val result = UpdateInfoParser.parseLatestResponse(payload)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(8, info.versionCode)
        assertEquals("1.8.0", info.versionName)
        assertEquals("https://example.com/app.apk", info.apkUrl)
    }

    @Test
    fun parseLatestResponse_withInvalidPayload_returnsFailure() {
        val payload = """{"version_code":0,"version_name":"","apk_url":""}"""

        val result = UpdateInfoParser.parseLatestResponse(payload)

        assertTrue(result.isFailure)
    }

    @Test
    fun decideUpdateAction_whenSameOrOlderVersion_returnsNoUpdate() {
        val currentVersionCode = 8
        val info = UpdateInfo(
            versionCode = 8,
            versionName = "1.8.0",
            apkUrl = "https://example.com/app.apk",
            releaseNotes = "",
            publishedAt = "",
            sha256 = ""
        )

        val action = UpdateInfoParser.decideUpdateAction(currentVersionCode, info)

        assertEquals(UpdateAction.NO_UPDATE, action)
    }

    @Test
    fun decideUpdateAction_whenNewerVersion_returnsDownload() {
        val currentVersionCode = 8
        val info = UpdateInfo(
            versionCode = 9,
            versionName = "1.9.0",
            apkUrl = "https://example.com/app.apk",
            releaseNotes = "",
            publishedAt = "",
            sha256 = ""
        )

        val action = UpdateInfoParser.decideUpdateAction(currentVersionCode, info)

        assertEquals(UpdateAction.DOWNLOAD_UPDATE, action)
    }
}

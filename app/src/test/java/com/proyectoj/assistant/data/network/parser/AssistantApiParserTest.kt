package com.proyectoj.assistant.data.network.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantApiParserTest {
    @Test
    fun parseBuildSummaries_withValidPayload_returnsSummaries() {
        val payload = """
            {
              "builds": [
                {
                  "branch": "auto/test01",
                  "status": "running",
                  "message": "working",
                  "updated_at": "2026-03-14T00:00:00Z",
                  "log_count": 3
                }
              ]
            }
        """.trimIndent()

        val result = AssistantApiParser.parseBuildSummaries(payload)

        assertTrue(result.isSuccess)
        val summaries = result.getOrThrow()
        assertEquals(1, summaries.size)
        assertEquals("auto/test01", summaries.first().branch)
    }

    @Test
    fun parseRollbackStatus_withValidPayload_returnsStatus() {
        val payload = """
            {
              "rollback_id": "rollback/1",
              "target_job_branch": "auto/test01",
              "status": "reverting",
              "message": "working",
              "updated_at": "2026-03-14T00:00:00Z",
              "finished_at": "",
              "repos": [
                {
                  "repo_name": "assistant-server",
                  "status": "done",
                  "message": "ok",
                  "external_side_effects": ["android_ota_publish"]
                }
              ],
              "validation": [
                {
                  "scope": "assistant-server",
                  "command": "git status",
                  "status": "passed",
                  "message": "clean"
                }
              ]
            }
        """.trimIndent()

        val result = AssistantApiParser.parseRollbackStatus(payload)

        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertEquals("rollback/1", status.rollbackId)
        assertEquals(1, status.repos.size)
        assertEquals(1, status.validation.size)
    }

    @Test
    fun parseHttpError_prefersDetailMessage() {
        val body = """{"detail":"specific error"}"""

        val error = AssistantApiParser.parseHttpError(409, "Conflict", body)

        assertEquals("specific error", error)
    }
}

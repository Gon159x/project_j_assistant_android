package com.proyectoj.assistant.data.network.parser

import com.proyectoj.assistant.data.network.dto.BuildLogsChunk
import com.proyectoj.assistant.data.network.dto.BuildSummary
import com.proyectoj.assistant.data.network.dto.LatestRollbackInfo
import com.proyectoj.assistant.data.network.dto.LatestRollbackJob
import com.proyectoj.assistant.data.network.dto.RollbackRepo
import com.proyectoj.assistant.data.network.dto.RollbackStatus
import com.proyectoj.assistant.data.network.dto.RollbackValidation
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

object AssistantApiParser {
    fun parseBuildSummaries(rawBody: String): Result<List<BuildSummary>> {
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

    fun parseBuildLogsChunk(rawBody: String): Result<BuildLogsChunk> {
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

    fun parseLatestRollbackInfo(rawBody: String): Result<LatestRollbackInfo> {
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

    fun parseRollbackStatus(rawBody: String): Result<RollbackStatus> {
        return try {
            Result.success(parseRollbackStatusObject(JSONObject(rawBody)))
        } catch (ex: JSONException) {
            Result.failure(IOException("Invalid JSON format from rollback status endpoint.", ex))
        }
    }

    fun parseHttpError(code: Int, message: String, body: String): String {
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
}

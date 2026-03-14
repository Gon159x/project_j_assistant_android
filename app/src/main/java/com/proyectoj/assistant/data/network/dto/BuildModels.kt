package com.proyectoj.assistant.data.network.dto

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

package com.proyectoj.assistant

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.proyectoj.assistant.network.ApiClient
import com.proyectoj.assistant.network.BuildSummary
import com.proyectoj.assistant.network.LatestRollbackInfo
import com.proyectoj.assistant.network.RollbackStatus
import com.proyectoj.assistant.network.UpdateAction
import com.proyectoj.assistant.network.UpdateInfo
import com.proyectoj.assistant.network.UpdateInfoParser
import com.proyectoj.assistant.speech.SpeechHandler
import com.proyectoj.assistant.speech.TtsHandler
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var tabLayoutMain: TabLayout
    private lateinit var assistantTabContent: LinearLayout
    private lateinit var logsTabContent: LinearLayout
    private lateinit var rollbackTabContent: LinearLayout

    private lateinit var speakButton: Button
    private lateinit var sendTextButton: Button
    private lateinit var chauMundoButton: MaterialButton
    private lateinit var holaPaButton: MaterialButton
    private lateinit var argentinaButton: MaterialButton
    private lateinit var checkUpdateButton: Button
    private lateinit var messageInput: EditText
    private lateinit var recognizedTextView: TextView
    private lateinit var responseTextView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var updateProgressView: ProgressBar
    private lateinit var updateProgressTextView: TextView

    private lateinit var buildMonitorStatusView: TextView
    private lateinit var activeBuildsView: TextView
    private lateinit var activeBuildsListView: ListView
    private lateinit var buildLogBranchView: TextView
    private lateinit var buildLogsSummaryView: TextView
    private lateinit var buildLogsView: TextView
    private lateinit var buildLogsProgressView: ProgressBar
    private lateinit var refreshBuildLogsButton: MaterialButton
    private lateinit var activeBuildsAdapter: ArrayAdapter<String>
    private lateinit var rollbackStatusView: TextView
    private lateinit var rollbackReasonView: TextView
    private lateinit var rollbackLastRequestTitleView: TextView
    private lateinit var rollbackLastRequestTimeView: TextView
    private lateinit var rollbackLastRequestReposView: TextView
    private lateinit var rollbackWarningView: TextView
    private lateinit var rollbackResultView: TextView
    private lateinit var rollbackRepoDetailsView: TextView
    private lateinit var rollbackProgressView: ProgressBar
    private lateinit var refreshRollbackButton: MaterialButton
    private lateinit var rollbackConfirmButton: MaterialButton

    private lateinit var apiClient: ApiClient
    private lateinit var speechHandler: SpeechHandler
    private lateinit var ttsHandler: TtsHandler
    private lateinit var networkExecutor: ExecutorService
    private lateinit var buildMonitorExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var downloadManager: DownloadManager
    private var currentDownloadId: Long? = null
    private var currentDownloadedFileName: String? = null
    private var downloadProgressRunnable: Runnable? = null
    private var isTtsReady: Boolean = false

    private var buildPollingEnabled: Boolean = false
    private var buildPollingInFlight: Boolean = false
    private var buildPollingRunnable: Runnable? = null
    private var selectedBuildBranch: String? = null
    private var latestActiveBuilds: List<BuildSummary> = emptyList()
    private var buildLogsNextSeq: Int = 0
    private val buildLogBuffer: ArrayDeque<String> = ArrayDeque()
    private var rollbackPollingEnabled: Boolean = false
    private var rollbackPollingInFlight: Boolean = false
    private var rollbackPollingRunnable: Runnable? = null
    private var latestRollbackInfo: LatestRollbackInfo? = null
    private var latestRollbackStatus: RollbackStatus? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                return
            }
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val expectedId = currentDownloadId ?: return
            if (completedId != expectedId) {
                return
            }
            handleDownloadedApk(expectedId)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginSpeechFlow()
            } else {
                showError("Microphone permission is required.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayoutMain = findViewById(R.id.tabLayoutMain)
        assistantTabContent = findViewById(R.id.assistantTabContent)
        logsTabContent = findViewById(R.id.logsTabContent)
        rollbackTabContent = findViewById(R.id.rollbackTabContent)

        speakButton = findViewById(R.id.btnSpeak)
        sendTextButton = findViewById(R.id.btnSendText)
        chauMundoButton = findViewById(R.id.btnChauMundo)
        holaPaButton = findViewById(R.id.btnHolaPa)
        argentinaButton = findViewById(R.id.btnArgentina)
        checkUpdateButton = findViewById(R.id.btnCheckUpdate)
        messageInput = findViewById(R.id.etMessageInput)
        recognizedTextView = findViewById(R.id.tvRecognizedText)
        responseTextView = findViewById(R.id.tvResponseText)
        loadingView = findViewById(R.id.progressBar)
        updateProgressView = findViewById(R.id.progressBarUpdate)
        updateProgressTextView = findViewById(R.id.tvUpdateProgress)

        buildMonitorStatusView = findViewById(R.id.tvBuildMonitorStatus)
        activeBuildsView = findViewById(R.id.tvActiveBuilds)
        activeBuildsListView = findViewById(R.id.lvActiveBuilds)
        buildLogBranchView = findViewById(R.id.tvBuildLogBranch)
        buildLogsSummaryView = findViewById(R.id.tvBuildLogsSummary)
        buildLogsView = findViewById(R.id.tvBuildLogs)
        buildLogsProgressView = findViewById(R.id.progressBarBuildLogs)
        refreshBuildLogsButton = findViewById(R.id.btnRefreshBuildLogs)
        rollbackStatusView = findViewById(R.id.tvRollbackStatus)
        rollbackReasonView = findViewById(R.id.tvRollbackReason)
        rollbackLastRequestTitleView = findViewById(R.id.tvRollbackLastRequestTitle)
        rollbackLastRequestTimeView = findViewById(R.id.tvRollbackLastRequestTime)
        rollbackLastRequestReposView = findViewById(R.id.tvRollbackLastRequestRepos)
        rollbackWarningView = findViewById(R.id.tvRollbackWarning)
        rollbackResultView = findViewById(R.id.tvRollbackResult)
        rollbackRepoDetailsView = findViewById(R.id.tvRollbackRepoDetails)
        rollbackProgressView = findViewById(R.id.progressBarRollback)
        refreshRollbackButton = findViewById(R.id.btnRefreshRollback)
        rollbackConfirmButton = findViewById(R.id.btnRollbackConfirm)
        activeBuildsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        activeBuildsListView.adapter = activeBuildsAdapter
        activeBuildsListView.setOnItemClickListener { _, _, position, _ ->
            val selected = latestActiveBuilds.getOrNull(position) ?: return@setOnItemClickListener
            if (selected.branch == selectedBuildBranch) {
                return@setOnItemClickListener
            }
            selectedBuildBranch = selected.branch
            resetBuildLogsState()
            triggerBuildPollNow()
        }

        apiClient = ApiClient(applicationContext)
        speechHandler = SpeechHandler(this)
        ttsHandler = TtsHandler(this) { ready ->
            isTtsReady = ready
            if (!ready) showError("TextToSpeech is not available.")
        }
        networkExecutor = Executors.newSingleThreadExecutor()
        buildMonitorExecutor = Executors.newSingleThreadExecutor()
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadCompleteFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloadCompleteFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, downloadCompleteFilter)
        }

        setupTabs()

        speakButton.setOnClickListener {
            if (hasAudioPermission()) {
                beginSpeechFlow()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        checkUpdateButton.setOnClickListener {
            checkForUpdates()
        }

        sendTextButton.setOnClickListener {
            submitTypedMessage()
        }

        chauMundoButton.setOnClickListener {
            showInfo(getString(R.string.chau_mundo_button))
        }

        holaPaButton.setOnClickListener {
            showInfo(getString(R.string.hola_pa_message))
        }

        argentinaButton.setOnClickListener {
            showInfo(getString(R.string.argentina_message))
        }

        refreshBuildLogsButton.setOnClickListener {
            resetBuildLogsState()
            triggerBuildPollNow()
        }
        refreshRollbackButton.setOnClickListener {
            triggerRollbackPollNow()
        }
        rollbackConfirmButton.setOnClickListener {
            confirmRollback()
        }
    }

    private fun setupTabs() {
        tabLayoutMain.removeAllTabs()
        tabLayoutMain.addTab(tabLayoutMain.newTab().setText(getString(R.string.tab_assistant)))
        tabLayoutMain.addTab(tabLayoutMain.newTab().setText(getString(R.string.tab_codex_logs)))
        tabLayoutMain.addTab(tabLayoutMain.newTab().setText(getString(R.string.tab_revert)))
        tabLayoutMain.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectMainTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) {
                    triggerBuildPollNow()
                } else if (tab.position == 2) {
                    triggerRollbackPollNow()
                }
            }
        })
        selectMainTab(0)
    }

    private fun selectMainTab(position: Int) {
        val logsSelected = position == 1
        val rollbackSelected = position == 2
        assistantTabContent.visibility = if (!logsSelected && !rollbackSelected) View.VISIBLE else View.GONE
        logsTabContent.visibility = if (logsSelected) View.VISIBLE else View.GONE
        rollbackTabContent.visibility = if (rollbackSelected) View.VISIBLE else View.GONE
        if (logsSelected) {
            startBuildPolling()
            stopRollbackPolling()
        } else if (rollbackSelected) {
            stopBuildPolling()
            startRollbackPolling()
        } else {
            stopBuildPolling()
            stopRollbackPolling()
        }
    }

    private fun startBuildPolling() {
        if (buildPollingEnabled) {
            return
        }
        buildPollingEnabled = true
        triggerBuildPollNow()
    }

    private fun stopBuildPolling() {
        buildPollingEnabled = false
        buildPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        buildPollingRunnable = null
        buildPollingInFlight = false
        setBuildLogsLoading(false)
    }

    private fun triggerBuildPollNow() {
        if (!buildPollingEnabled) {
            return
        }
        buildPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        pollBuildStatusOnce()
    }

    private fun scheduleNextBuildPoll() {
        if (!buildPollingEnabled) {
            return
        }
        val runnable = Runnable { pollBuildStatusOnce() }
        buildPollingRunnable = runnable
        mainHandler.postDelayed(runnable, BUILD_POLL_INTERVAL_MS)
    }

    private fun pollBuildStatusOnce() {
        if (!buildPollingEnabled || buildPollingInFlight) {
            scheduleNextBuildPoll()
            return
        }

        buildPollingInFlight = true
        setBuildLogsLoading(true)

        buildMonitorExecutor.execute {
            var activeBuilds: List<BuildSummary> = emptyList()
            var buildBranch: String? = null
            var buildStatus = ""
            var newLogs: List<String> = emptyList()
            var nextSeq = buildLogsNextSeq
            var errorText: String? = null

            val activeResult = apiClient.fetchActiveBuilds()
            activeResult.onSuccess { builds ->
                activeBuilds = builds
            }.onFailure { error ->
                errorText = error.message ?: getString(R.string.codex_logs_error_generic)
            }

            if (errorText == null && activeBuilds.isNotEmpty()) {
                val branch = chooseBranchToTrack(activeBuilds)
                buildBranch = branch
                val logsResult = apiClient.fetchBuildLogs(branch, buildLogsNextSeq)
                logsResult.onSuccess { chunk ->
                    buildStatus = chunk.status
                    newLogs = chunk.logs
                    nextSeq = chunk.nextSeq
                }.onFailure { error ->
                    errorText = error.message ?: getString(R.string.codex_logs_error_generic)
                }
            }

            runOnUiThread {
                buildPollingInFlight = false
                setBuildLogsLoading(false)

                if (!buildPollingEnabled) {
                    return@runOnUiThread
                }

                if (errorText != null) {
                    buildMonitorStatusView.text = getString(R.string.codex_logs_error_prefix, errorText)
                    scheduleNextBuildPoll()
                    return@runOnUiThread
                }

                renderActiveBuilds(activeBuilds)

                if (activeBuilds.isEmpty()) {
                    selectedBuildBranch = null
                    buildMonitorStatusView.text = getString(R.string.codex_logs_no_active_builds)
                    buildLogBranchView.text = getString(R.string.codex_logs_branch_none)
                    if (buildLogBuffer.isEmpty()) {
                        buildLogsView.text = getString(R.string.codex_logs_waiting)
                    }
                    scheduleNextBuildPoll()
                    return@runOnUiThread
                }

                selectedBuildBranch = buildBranch
                buildLogsNextSeq = nextSeq
                appendBuildLogs(newLogs)
                val trackedBranch = selectedBuildBranch.orEmpty()
                buildLogBranchView.text = getString(R.string.codex_logs_branch_prefix, trackedBranch)
                buildMonitorStatusView.text = getString(
                    R.string.codex_logs_tracking_status,
                    trackedBranch,
                    buildStatus.ifBlank { "running" }
                )
                scheduleNextBuildPoll()
            }
        }
    }

    private fun chooseBranchToTrack(builds: List<BuildSummary>): String {
        val selected = selectedBuildBranch
        if (!selected.isNullOrBlank() && builds.any { it.branch == selected }) {
            return selected
        }
        val first = builds.first().branch
        if (selected != first) {
            resetBuildLogsState()
        }
        return first
    }

    private fun renderActiveBuilds(builds: List<BuildSummary>) {
        latestActiveBuilds = builds
        if (builds.isEmpty()) {
            activeBuildsView.text = getString(R.string.codex_logs_no_active_builds)
            activeBuildsAdapter.clear()
            return
        }
        activeBuildsView.text = getString(R.string.codex_logs_active_builds_count, builds.size)
        val rows = builds.map { summary ->
            val marker = if (summary.branch == selectedBuildBranch) "* " else ""
            "$marker${summary.branch} [${summary.status}]"
        }
        activeBuildsAdapter.clear()
        activeBuildsAdapter.addAll(rows)
        activeBuildsAdapter.notifyDataSetChanged()
    }

    private fun resetBuildLogsState() {
        buildLogsNextSeq = 0
        buildLogBuffer.clear()
        buildLogsView.text = getString(R.string.codex_logs_waiting)
        buildLogsSummaryView.text = getString(R.string.codex_logs_summary_empty)
        buildLogBranchView.text = getString(R.string.codex_logs_branch_none)
    }

    private fun appendBuildLogs(newLogs: List<String>) {
        if (newLogs.isEmpty()) {
            if (buildLogBuffer.isEmpty()) {
                buildLogsView.text = getString(R.string.codex_logs_waiting)
                buildLogsSummaryView.text = getString(R.string.codex_logs_summary_empty)
            }
            return
        }

        for (rawLine in newLogs) {
            val line = formatBuildLogLine(rawLine) ?: continue
            if (buildLogBuffer.peekLast() == line) {
                continue
            }
            buildLogBuffer.addLast(line)
            while (buildLogBuffer.size > MAX_VISIBLE_BUILD_LOG_LINES) {
                buildLogBuffer.removeFirst()
            }
        }

        val orderedLines = buildLogBuffer.toList().asReversed()
        buildLogsView.text = orderedLines.joinToString("\n")
        renderBuildLogsSummary(orderedLines)
    }

    private fun renderBuildLogsSummary(lines: List<String>) {
        if (lines.isEmpty()) {
            buildLogsSummaryView.text = getString(R.string.codex_logs_summary_empty)
            return
        }
        var errors = 0
        var warnings = 0
        var ok = 0
        for (line in lines) {
            when {
                line.startsWith("[ERROR]") -> errors += 1
                line.startsWith("[WARN]") -> warnings += 1
                line.startsWith("[OK]") -> ok += 1
            }
        }
        buildLogsSummaryView.text = getString(
            R.string.codex_logs_summary_format,
            lines.size,
            errors,
            warnings,
            ok
        )
    }

    private fun startRollbackPolling() {
        if (rollbackPollingEnabled) {
            return
        }
        rollbackPollingEnabled = true
        triggerRollbackPollNow()
    }

    private fun stopRollbackPolling() {
        rollbackPollingEnabled = false
        rollbackPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        rollbackPollingRunnable = null
        rollbackPollingInFlight = false
        setRollbackLoading(false)
    }

    private fun triggerRollbackPollNow() {
        if (!rollbackPollingEnabled) {
            return
        }
        rollbackPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRollbackStateOnce()
    }

    private fun scheduleNextRollbackPoll() {
        if (!rollbackPollingEnabled) {
            return
        }
        val runnable = Runnable { pollRollbackStateOnce() }
        rollbackPollingRunnable = runnable
        mainHandler.postDelayed(runnable, BUILD_POLL_INTERVAL_MS)
    }

    private fun pollRollbackStateOnce() {
        if (!rollbackPollingEnabled || rollbackPollingInFlight) {
            scheduleNextRollbackPoll()
            return
        }
        rollbackPollingInFlight = true
        setRollbackLoading(true)
        buildMonitorExecutor.execute {
            var info: LatestRollbackInfo? = null
            var rollbackStatus: RollbackStatus? = null
            var errorText: String? = null
            apiClient.fetchLatestRollbackInfo()
                .onSuccess { value ->
                    info = value
                    val activeRollback = value.rollback
                    if (activeRollback != null && activeRollback.status == "reverting") {
                        apiClient.fetchRollbackStatus(activeRollback.rollbackId)
                            .onSuccess { status -> rollbackStatus = status }
                            .onFailure { error ->
                                errorText = error.message ?: getString(R.string.rollback_error_generic)
                            }
                    } else {
                        rollbackStatus = activeRollback
                    }
                }
                .onFailure { error ->
                    errorText = error.message ?: getString(R.string.rollback_error_generic)
                }
            runOnUiThread {
                rollbackPollingInFlight = false
                setRollbackLoading(false)
                if (!rollbackPollingEnabled) {
                    return@runOnUiThread
                }
                if (errorText != null) {
                    rollbackStatusView.text = getString(R.string.rollback_error_generic)
                    rollbackReasonView.text = getString(R.string.rollback_reason_prefix, errorText ?: "")
                    scheduleNextRollbackPoll()
                    return@runOnUiThread
                }
                latestRollbackInfo = info
                latestRollbackStatus = rollbackStatus
                renderRollbackInfo(info, rollbackStatus)
                if (rollbackStatus?.status == "reverting") {
                    scheduleNextRollbackPoll()
                }
            }
        }
    }

    private fun renderRollbackInfo(info: LatestRollbackInfo?, rollbackStatus: RollbackStatus?) {
        if (info == null || info.job == null) {
            rollbackStatusView.text = getString(R.string.rollback_empty)
            rollbackReasonView.text = ""
            rollbackLastRequestTitleView.text = getString(R.string.rollback_last_request_none)
            rollbackLastRequestTimeView.text = ""
            rollbackLastRequestReposView.text = ""
            rollbackWarningView.visibility = View.GONE
            rollbackResultView.text = ""
            rollbackRepoDetailsView.text = ""
            rollbackConfirmButton.isEnabled = false
            refreshRollbackButton.isEnabled = true
            return
        }
        val job = info.job
        rollbackStatusView.text = getString(
            R.string.rollback_status_prefix,
            rollbackStatus?.status ?: info.availabilityStatus
        )
        rollbackReasonView.text = getString(
            R.string.rollback_reason_prefix,
            if (rollbackStatus != null) rollbackStatus.message else info.reason
        )
        rollbackLastRequestTitleView.text = "${getString(R.string.rollback_last_request_title)}: ${job.title}"
        rollbackLastRequestTimeView.text = getString(R.string.rollback_last_request_time, job.updatedAt)
        rollbackLastRequestReposView.text = getString(
            R.string.rollback_last_request_repos,
            if (job.repos.isEmpty()) "-" else job.repos.joinToString(", ")
        )
        rollbackWarningView.visibility = if (job.externalSideEffects.isEmpty()) View.GONE else View.VISIBLE
        rollbackResultView.text = if (rollbackStatus == null) {
            getString(R.string.rollback_result_prefix, info.reason)
        } else {
            getString(R.string.rollback_result_prefix, rollbackStatus.message)
        }
        val repoLines = mutableListOf<String>()
        rollbackStatus?.repos?.forEach { repo ->
            repoLines.add(getString(R.string.rollback_repo_line, repo.repoName, repo.status, repo.message))
        }
        rollbackStatus?.validation?.forEach { validation ->
            repoLines.add(getString(R.string.rollback_validation_line, validation.scope, validation.status))
        }
        rollbackRepoDetailsView.text = repoLines.joinToString("\n")
        rollbackConfirmButton.isEnabled = info.canRevert && rollbackStatus?.status != "reverting"
        refreshRollbackButton.isEnabled = rollbackStatus?.status != "reverting"
    }

    private fun confirmRollback() {
        val info = latestRollbackInfo ?: return
        val job = info.job ?: return
        if (!info.canRevert) {
            showError(info.reason)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rollback_confirm))
            .setMessage(getString(R.string.rollback_confirmation_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rollback_confirm) { _, _ ->
                startRollback(job.branch, job.updatedAt)
            }
            .show()
    }

    private fun startRollback(branch: String, updatedAt: String) {
        setRollbackLoading(true)
        buildMonitorExecutor.execute {
            val result = apiClient.startLatestRollback(branch, updatedAt)
            runOnUiThread {
                setRollbackLoading(false)
                result.onSuccess { status ->
                    latestRollbackStatus = status
                    rollbackStatusView.text = getString(R.string.rollback_running)
                    rollbackReasonView.text = getString(R.string.rollback_reason_prefix, status.message)
                    triggerRollbackPollNow()
                }.onFailure { error ->
                    showError(error.message ?: getString(R.string.rollback_error_generic))
                    triggerRollbackPollNow()
                }
            }
        }
    }

    private fun setRollbackLoading(loading: Boolean) {
        rollbackProgressView.visibility = if (loading) View.VISIBLE else View.GONE
        refreshRollbackButton.isEnabled = !loading
        rollbackConfirmButton.isEnabled = !loading && (latestRollbackInfo?.canRevert == true)
    }

    private fun formatBuildLogLine(rawLine: String): String? {
        var clean = ANSI_ESCAPE_REGEX.replace(rawLine, "").trim()
        if (clean.isBlank()) {
            return null
        }
        clean = LEADING_TIMESTAMP_REGEX.replace(clean, "").trim()
        clean = LEADING_NOISE_REGEX.replace(clean, "").trim()
        clean = PATH_REGEX.replace(clean, "<path>")
        clean = URL_REGEX.replace(clean, "<url>")
        clean = SHA_REGEX.replace(clean, "<sha>")
        clean = MULTISPACE_REGEX.replace(clean, " ").trim()
        if (clean.isBlank()) {
            return null
        }

        val level = classifyLogLevel(clean)
        val summary = summarizeCodexAction(clean, level)
        return "[$level] $summary"
    }

    private fun classifyLogLevel(line: String): String {
        val value = line.lowercase()
        return when {
            value.contains("error") || value.contains("failed") || value.contains("exception") -> "ERROR"
            value.contains("warn") || value.contains("retry") || value.contains("timeout") -> "WARN"
            value.contains("success") || value.contains("completed") || value.contains("done") || value.contains("passed") -> "OK"
            else -> "INFO"
        }
    }

    private fun summarizeCodexAction(line: String, level: String): String {
        val value = line.lowercase()
        val command = extractCommand(line)

        if (level == "ERROR") {
            return "Bloqueado por error: ${compactDetail(line)}"
        }

        return when {
            value.contains("apply_patch") || value.contains("patch") || value.contains("update file") ||
                value.contains("add file") || value.contains("delete file") || value.contains("edit") ->
                "Aplicando cambios de codigo"

            value.contains("rg ") || value.contains("grep") || value.contains("find ") ||
                value.contains("get-content") || value.contains("cat ") || value.contains("ls ") ||
                value.contains("scan") || value.contains("inspect") || value.contains("read") ->
                "Revisando codigo y archivos"

            value.contains("test") || value.contains("gradle") || value.contains("assemble") ||
                value.contains("build") || value.contains("compile") || value.contains("lint") ->
                "Validando build y pruebas"

            value.contains("git") || value.contains("branch") || value.contains("commit") ||
                value.contains("diff") || value.contains("status") ->
                "Revisando estado de git"

            value.contains("http") || value.contains("fetch") || value.contains("request") ||
                value.contains("download") || value.contains("endpoint") ->
                "Consultando servicios y datos"

            value.contains("plan") || value.contains("step") || value.contains("progress") ->
                "Planificando siguientes pasos"

            value.contains("done") || value.contains("completed") || value.contains("success") ||
                value.contains("passed") ->
                "Paso completado"

            command != null -> "Ejecutando comando: $command"
            else -> compactDetail(line)
        }
    }

    private fun extractCommand(line: String): String? {
        val normalized = line.trim()
        val markerIndex = normalized.indexOf("command:", ignoreCase = true)
        if (markerIndex >= 0) {
            val commandPart = normalized.substring(markerIndex + "command:".length).trim().trim('"')
            if (commandPart.isNotBlank()) {
                return compactDetail(commandPart)
            }
        }
        val psIndex = normalized.indexOf("powershell", ignoreCase = true)
        if (psIndex >= 0) {
            return "powershell"
        }
        return null
    }

    private fun compactDetail(raw: String): String {
        val cleaned = raw.trim().replace(Regex("^[\\[\\](){}\\-:]+"), "").trim()
        return if (cleaned.length > MAX_SUMMARY_DETAIL_LENGTH) {
            cleaned.take(MAX_SUMMARY_DETAIL_LENGTH).trimEnd() + "..."
        } else {
            cleaned
        }
    }

    private fun setBuildLogsLoading(loading: Boolean) {
        buildLogsProgressView.visibility = if (loading) View.VISIBLE else View.GONE
        refreshBuildLogsButton.isEnabled = !loading
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun beginSpeechFlow() {
        setLoading(false)
        responseTextView.text = getString(R.string.waiting_response)
        speechHandler.startListening(
            onResult = { recognizedText ->
                runOnUiThread {
                    recognizedTextView.text = recognizedText
                    setLoading(true)
                }
                sendToServer(recognizedText)
            },
            onError = { error ->
                runOnUiThread {
                    showError(error)
                }
            }
        )
    }

    // STT -> HTTP -> TTS flow is done here to keep activity logic explicit.
    private fun sendToServer(message: String) {
        networkExecutor.execute {
            val result = apiClient.postMessage(message)
            runOnUiThread {
                setLoading(false)
                result.onSuccess { response ->
                    responseTextView.text = response
                    if (isTtsReady) {
                        ttsHandler.speak(response)
                    } else {
                        showError("TextToSpeech is not ready.")
                    }
                }.onFailure { error ->
                    showError(error.message ?: "Unknown network error.")
                }
            }
        }
    }

    private fun submitTypedMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isBlank()) {
            showError(getString(R.string.empty_message_error))
            return
        }
        recognizedTextView.text = message
        setLoading(true)
        sendToServer(message)
    }

    private fun checkForUpdates() {
        setLoading(true)
        responseTextView.text = getString(R.string.update_checking)
        networkExecutor.execute {
            val result = apiClient.fetchLatestUpdateInfo()
            runOnUiThread {
                setLoading(false)
                result.onSuccess { update ->
                    when (UpdateInfoParser.decideUpdateAction(BuildConfig.VERSION_CODE, update)) {
                        UpdateAction.NO_UPDATE -> showInfo(getString(R.string.update_not_available))
                        UpdateAction.DOWNLOAD_UPDATE -> startUpdateDownload(update)
                    }
                }.onFailure { error ->
                    showError(error.message ?: getString(R.string.update_download_failed))
                }
            }
        }
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        val fileName = "assistant-${update.versionName}-${update.versionCode}.apk"
        currentDownloadedFileName = fileName
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle(getString(R.string.app_name))
            .setDescription(getString(R.string.update_download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)
        currentDownloadId = downloadId
        checkUpdateButton.isEnabled = false
        startDownloadProgressTracking(downloadId)
        showInfo(getString(R.string.update_available, update.versionName, update.versionCode))
        Toast.makeText(this, getString(R.string.update_download_started), Toast.LENGTH_SHORT).show()
    }

    private fun startDownloadProgressTracking(downloadId: Long) {
        stopDownloadProgressTracking()
        updateProgressTextView.text = getString(R.string.update_download_progress_pending)
        updateProgressTextView.visibility = View.VISIBLE
        updateProgressView.visibility = View.VISIBLE
        updateProgressView.isIndeterminate = true
        updateProgressView.progress = 0

        val runnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        showError(getString(R.string.update_download_failed))
                        stopDownloadProgressTracking()
                        return
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_PENDING -> {
                            updateProgressView.isIndeterminate = true
                            updateProgressTextView.text = getString(R.string.update_download_progress_pending)
                        }

                        DownloadManager.STATUS_PAUSED -> {
                            updateProgressView.isIndeterminate = true
                            updateProgressTextView.text = getString(R.string.update_download_paused)
                        }

                        DownloadManager.STATUS_RUNNING -> {
                            val downloadedBytes = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            )
                            val totalBytes = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            )
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (totalBytes > 0) {
                                updateProgressView.isIndeterminate = false
                                updateProgressView.progress = progress
                                updateProgressTextView.text = getString(
                                    R.string.update_download_progress_with_size,
                                    progress,
                                    Formatter.formatShortFileSize(this@MainActivity, downloadedBytes),
                                    Formatter.formatShortFileSize(this@MainActivity, totalBytes)
                                )
                            } else {
                                updateProgressView.isIndeterminate = true
                                updateProgressTextView.text = getString(
                                    R.string.update_download_progress_percent,
                                    progress
                                )
                            }
                        }

                        DownloadManager.STATUS_SUCCESSFUL -> {
                            updateProgressView.isIndeterminate = false
                            updateProgressView.progress = 100
                            updateProgressTextView.text = getString(R.string.update_download_completed)
                            return
                        }

                        DownloadManager.STATUS_FAILED -> {
                            showError(getString(R.string.update_download_failed))
                            stopDownloadProgressTracking()
                            return
                        }
                    }
                } ?: run {
                    showError(getString(R.string.update_download_failed))
                    stopDownloadProgressTracking()
                    return
                }

                mainHandler.postDelayed(this, 500L)
            }
        }

        downloadProgressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopDownloadProgressTracking(clearUi: Boolean = true) {
        downloadProgressRunnable?.let { mainHandler.removeCallbacks(it) }
        downloadProgressRunnable = null
        if (clearUi) {
            updateProgressView.visibility = View.GONE
            updateProgressView.isIndeterminate = true
            updateProgressView.progress = 0
            updateProgressTextView.visibility = View.GONE
            updateProgressTextView.text = ""
            currentDownloadId = null
            checkUpdateButton.isEnabled = true
        }
    }

    private fun handleDownloadedApk(downloadId: Long) {
        stopDownloadProgressTracking(clearUi = false)
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                showError(getString(R.string.update_download_failed))
                stopDownloadProgressTracking()
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                showError(getString(R.string.update_download_failed))
                stopDownloadProgressTracking()
                return
            }
        }

        val fileName = currentDownloadedFileName ?: run {
            showError(getString(R.string.update_download_failed))
            stopDownloadProgressTracking()
            return
        }
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!apkFile.exists()) {
            showError(getString(R.string.update_download_failed))
            stopDownloadProgressTracking()
            return
        }
        installApk(apkFile)
        stopDownloadProgressTracking()
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            showError(getString(R.string.update_unknown_sources_required))
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(installIntent)
        } catch (_: Exception) {
            showError(getString(R.string.update_install_failed))
        }
    }

    private fun setLoading(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
        speakButton.isEnabled = !loading
        sendTextButton.isEnabled = !loading
        chauMundoButton.isEnabled = !loading
        holaPaButton.isEnabled = !loading
        argentinaButton.isEnabled = !loading
        checkUpdateButton.isEnabled = !loading
        messageInput.isEnabled = !loading
    }

    private fun showError(message: String) {
        responseTextView.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showInfo(message: String) {
        responseTextView.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Important cleanup for SpeechRecognizer and TTS lifecycle resources.
        stopBuildPolling()
        stopRollbackPolling()
        stopDownloadProgressTracking()
        unregisterReceiver(downloadReceiver)
        speechHandler.shutdown()
        ttsHandler.shutdown()
        networkExecutor.shutdown()
        buildMonitorExecutor.shutdown()
    }

    companion object {
        private const val BUILD_POLL_INTERVAL_MS = 2_000L
        private const val MAX_VISIBLE_BUILD_LOG_LINES = 80
        private const val MAX_SUMMARY_DETAIL_LENGTH = 140
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*m")
        private val LEADING_TIMESTAMP_REGEX = Regex("^\\[?\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?Z?\\]?\\s*")
        private val LEADING_NOISE_REGEX = Regex("^(?:\\[[A-Z]+\\]|[A-Z]+:|\\d+\\s*\\|)\\s*")
        private val PATH_REGEX = Regex("([A-Za-z]:\\\\[^\\s]+|/[^\\s]+)")
        private val URL_REGEX = Regex("https?://\\S+")
        private val SHA_REGEX = Regex("\\b[a-f0-9]{7,40}\\b", RegexOption.IGNORE_CASE)
        private val MULTISPACE_REGEX = Regex("\\s+")
    }
}

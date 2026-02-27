package com.proyectoj.assistant

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.proyectoj.assistant.network.ApiClient
import com.proyectoj.assistant.network.UpdateAction
import com.proyectoj.assistant.network.UpdateInfo
import com.proyectoj.assistant.network.UpdateInfoParser
import com.proyectoj.assistant.speech.SpeechHandler
import com.proyectoj.assistant.speech.TtsHandler
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var speakButton: Button
    private lateinit var sendTextButton: Button
    private lateinit var checkUpdateButton: Button
    private lateinit var messageInput: EditText
    private lateinit var recognizedTextView: TextView
    private lateinit var responseTextView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var updateProgressView: ProgressBar
    private lateinit var updateProgressTextView: TextView

    private lateinit var apiClient: ApiClient
    private lateinit var speechHandler: SpeechHandler
    private lateinit var ttsHandler: TtsHandler
    private lateinit var networkExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var downloadManager: DownloadManager
    private var currentDownloadId: Long? = null
    private var currentDownloadedFileName: String? = null
    private var downloadProgressRunnable: Runnable? = null
    private var isTtsReady: Boolean = false

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

        speakButton = findViewById(R.id.btnSpeak)
        sendTextButton = findViewById(R.id.btnSendText)
        checkUpdateButton = findViewById(R.id.btnCheckUpdate)
        messageInput = findViewById(R.id.etMessageInput)
        recognizedTextView = findViewById(R.id.tvRecognizedText)
        responseTextView = findViewById(R.id.tvResponseText)
        loadingView = findViewById(R.id.progressBar)
        updateProgressView = findViewById(R.id.progressBarUpdate)
        updateProgressTextView = findViewById(R.id.tvUpdateProgress)

        apiClient = ApiClient(applicationContext)
        speechHandler = SpeechHandler(this)
        ttsHandler = TtsHandler(this) { ready ->
            isTtsReady = ready
            if (!ready) showError("TextToSpeech is not available.")
        }
        networkExecutor = Executors.newSingleThreadExecutor()
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadCompleteFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloadCompleteFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, downloadCompleteFilter)
        }

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
        stopDownloadProgressTracking()
        unregisterReceiver(downloadReceiver)
        speechHandler.shutdown()
        ttsHandler.shutdown()
        networkExecutor.shutdown()
    }
}

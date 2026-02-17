package com.proyectoj.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.proyectoj.assistant.network.ApiClient
import com.proyectoj.assistant.speech.SpeechHandler
import com.proyectoj.assistant.speech.TtsHandler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var speakButton: Button
    private lateinit var recognizedTextView: TextView
    private lateinit var responseTextView: TextView
    private lateinit var loadingView: ProgressBar

    private lateinit var apiClient: ApiClient
    private lateinit var speechHandler: SpeechHandler
    private lateinit var ttsHandler: TtsHandler
    private lateinit var networkExecutor: ExecutorService

    private var isTtsReady: Boolean = false

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
        recognizedTextView = findViewById(R.id.tvRecognizedText)
        responseTextView = findViewById(R.id.tvResponseText)
        loadingView = findViewById(R.id.progressBar)

        apiClient = ApiClient(applicationContext)
        speechHandler = SpeechHandler(this)
        ttsHandler = TtsHandler(this) { ready ->
            isTtsReady = ready
            if (!ready) showError("TextToSpeech is not available.")
        }
        networkExecutor = Executors.newSingleThreadExecutor()

        speakButton.setOnClickListener {
            if (hasAudioPermission()) {
                beginSpeechFlow()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
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

    private fun setLoading(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
        speakButton.isEnabled = !loading
    }

    private fun showError(message: String) {
        responseTextView.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Important cleanup for SpeechRecognizer and TTS lifecycle resources.
        speechHandler.shutdown()
        ttsHandler.shutdown()
        networkExecutor.shutdown()
    }
}

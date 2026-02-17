package com.proyectoj.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsHandler(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {
    private var isReady: Boolean = false
    private val tts: TextToSpeech = TextToSpeech(context, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = tts.setLanguage(Locale.getDefault())
            isReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                langResult != TextToSpeech.LANG_NOT_SUPPORTED
            onReadyChanged(isReady)
        } else {
            isReady = false
            onReadyChanged(false)
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_response")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

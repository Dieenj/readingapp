package com.example.readingapp.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.readingapp.feature.settings.data.TTSSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation

/**
 * Công cụ TTS – tổng hợp văn bản thành tệp âm thanh.
 * Việc phát lại được xử lý độc quyền bởi AudioPlayer/ExoPlayer.
 */
class TextToSpeechEngine(private val context: Context) {

    private var textToSpeech: TextToSpeech? = null
    private var currentLanguage: String = "vi"
    @Volatile private var isInitialized = false
    private val synthesisMutex = Mutex()
    private val activeContinuation = AtomicReference<CancellableContinuation<Boolean>?>(null)

    // Khởi tạo công cụ TTS
    fun initialize(onReady: () -> Unit, onError: (Int) -> Unit) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
                textToSpeech?.setAudioAttributes(audioAttributes)
                isInitialized = true
                onReady()
            } else {
                android.util.Log.e("TextToSpeechEngine", "TTS initialization failed: $status")
                onError(status)
            }
        }
    }


    // Tổng hợp văn bản thành tệp âm thanh với cài đặt cụ thể
    suspend fun synthesizeTextToFile(
        text: String,
        language: String,
        settings: TTSSettings,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (!isInitialized || textToSpeech == null) {
            var attempts = 0
            while (!isInitialized && attempts < 50) {
                delay(100)
                attempts++
            }
            if (!isInitialized || textToSpeech == null) {
                android.util.Log.e("TextToSpeechEngine", "Synthesis failed: TTS engine not initialized after timeout")
                return false
            }
        }

        withContext(Dispatchers.IO) {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()
        }

        if (text.isBlank()) return false

        val cancelled = AtomicBoolean(false)

        return synthesisMutex.withLock {
            withContext(Dispatchers.Main) {
                if (language != currentLanguage) setLanguage(language)
                applySettings(settings, language)

                val result = kotlinx.coroutines.withTimeoutOrNull(20000L) {
                    suspendCancellableCoroutine { continuation ->
                        val utteranceId = UUID.randomUUID().toString()
                        activeContinuation.set(continuation)

                        val progressListener = object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                if (id == utteranceId && !cancelled.get()) onProgress(10)
                            }

                            override fun onDone(id: String?) {
                                if (id == utteranceId) {
                                    if (cancelled.get()) return
                                    activeContinuation.compareAndSet(continuation, null)
                                    onProgress(100)
                                    continuation.resume(outputFile.exists() && outputFile.length() > 0)
                                }
                            }

                            override fun onError(id: String?) {
                                if (id == utteranceId) {
                                    if (cancelled.get()) return
                                    activeContinuation.compareAndSet(continuation, null)
                                    android.util.Log.e("TextToSpeechEngine", "Synthesis error for utterance $id")
                                    continuation.resume(false)
                                }
                            }

                            override fun onError(id: String?, errorCode: Int) {
                                if (id == utteranceId) {
                                    if (cancelled.get()) return
                                    activeContinuation.compareAndSet(continuation, null)
                                    android.util.Log.e("TextToSpeechEngine", "Synthesis error $errorCode for utterance $id")
                                    continuation.resume(false)
                                }
                            }
                        }

                        textToSpeech?.setOnUtteranceProgressListener(progressListener)

                        val params = Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                        }

                        continuation.invokeOnCancellation {
                            cancelled.set(true)
                            activeContinuation.compareAndSet(continuation, null)
                            // Luôn resume để giải phóng synthesisMutex.withLock bên ngoài,
                            // kể cả khi coroutine đã bị cancel (isActive == false).
                            // resume() trên cancelled continuation là no-op an toàn.
                            try { continuation.resume(false) } catch (_: Exception) { }
                        }

                        val initResult = textToSpeech?.synthesizeToFile(text, params, outputFile, utteranceId)
                        if (initResult != TextToSpeech.SUCCESS) {
                            android.util.Log.e("TextToSpeechEngine", "synthesizeToFile call failed with result $initResult")
                            activeContinuation.compareAndSet(continuation, null)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }

                if (result == null) {
                    android.util.Log.e("TextToSpeechEngine", "Synthesis timed out (20s) for text: ${text.take(30)}... Stopping TTS.")
                    try { textToSpeech?.stop() } catch (_: Exception) {}
                    false
                } else {
                    result
                }
            }
        }
    }

    // Dừng tất cả tổng hợp TTS vật lý
    fun stop() {
        try {
            textToSpeech?.stop()
            val cont = activeContinuation.getAndSet(null)
            if (cont != null && cont.isActive) {
                cont.resume(false)
            }
        } catch (e: Exception) {
            android.util.Log.e("TextToSpeechEngine", "Failed to stop TTS", e)
        }
    }


    // Giải phóng tài nguyên TTS
    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
        activeContinuation.set(null)
    }

    // Thiết lập ngôn ngữ cho TTS
    private fun setLanguage(language: String) {
        val locale = languageToLocale(language)
        val result = textToSpeech?.setLanguage(locale)
        when (result) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                android.util.Log.w("TextToSpeechEngine", "Language $language is not supported or missing data. Falling back to en.")
                if (language != "en") {
                    textToSpeech?.language = Locale.US
                    currentLanguage = "en"
                }
            }
            else -> currentLanguage = language
        }
    }

    // Áp dụng các cài đặt tốc độ và độ cao giọng nói
    private fun applySettings(settings: TTSSettings, language: String) {
        textToSpeech?.setSpeechRate(settings.speechRate)
        textToSpeech?.setPitch(settings.pitch)
        val locale = languageToLocale(language)
        val voiceName = if (language == "en") settings.voiceNameEn else settings.voiceNameVi
        resolveVoice(locale, voiceName)?.let { textToSpeech?.voice = it }
    }

    // Tìm giọng nói phù hợp dựa trên ngôn ngữ và tên
    private fun resolveVoice(locale: Locale, voiceName: String?): Voice? {
        val voices = textToSpeech?.voices ?: return null
        if (voiceName != null) return voices.find { it.name == voiceName }
        return textToSpeech?.defaultVoice?.takeIf { it.locale.language == locale.language }
            ?: voices.filter { it.locale.language == locale.language }
                .maxByOrNull { it.quality * 100 - it.latency }
    }

    // Chuyển đổi mã ngôn ngữ sang đối tượng Locale
    private fun languageToLocale(language: String): Locale = when (language) {
        "en" -> Locale.US
        else -> Locale.forLanguageTag("vi-VN")
    }
}

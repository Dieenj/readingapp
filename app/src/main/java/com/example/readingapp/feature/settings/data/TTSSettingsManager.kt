package com.example.readingapp.feature.settings.data

import android.content.Context
import android.content.SharedPreferences

class TTSSettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "tts_settings"
        private const val KEY_VOICE_NAME_VI = "voice_name_vi"
        private const val KEY_VOICE_NAME_EN = "voice_name_en"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_SPEECH_RATE    = "speech_rate"
        private const val KEY_PITCH          = "pitch"
    }

    // Tải các cài đặt TTS từ SharedPreferences
    private fun loadSettings(): TTSSettings {
        return TTSSettings(
            voiceNameVi  = prefs.getString(KEY_VOICE_NAME_VI, null),
            voiceNameEn  = prefs.getString(KEY_VOICE_NAME_EN, null),
            autoPlayNext = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true),
            speechRate   = prefs.getFloat(KEY_SPEECH_RATE, 1.0f),
            pitch        = prefs.getFloat(KEY_PITCH, 1.0f)
        )
    }

    // Lưu các cài đặt TTS vào SharedPreferences
    fun saveSettings(settings: TTSSettings) {
        prefs.edit().apply {
            putString(KEY_VOICE_NAME_VI, settings.voiceNameVi)
            putString(KEY_VOICE_NAME_EN, settings.voiceNameEn)
            putBoolean(KEY_AUTO_PLAY_NEXT, settings.autoPlayNext)
            putFloat(KEY_SPEECH_RATE, settings.speechRate)
            putFloat(KEY_PITCH, settings.pitch)
            apply()
        }
    }

    // Lấy các cài đặt TTS hiện tại
    fun getCurrentSettings(): TTSSettings {
        return loadSettings()
    }
}


package com.example.readingapp.feature.settings.data

data class TTSSettings(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceNameVi: String? = null,
    val voiceNameEn: String? = null,
    val autoPlayNext: Boolean = true
)

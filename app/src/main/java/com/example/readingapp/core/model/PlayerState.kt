package com.example.readingapp.core.model

// Các trạng thái của trình phát nhạc
sealed class PlayerState {

    object Idle : PlayerState()

    data class Preparing(
        val contentId: String,
        val progress: Int = 0,
        val estimatedDuration: Long = 0
    ) : PlayerState()

    data class Buffering(
        val contentId: String
    ) : PlayerState()

    data class Playing(
        val contentId: String,
        val position: Long = 0,
        val duration: Long = 0
    ) : PlayerState()

    data class Paused(
        val contentId: String,
        val position: Long = 0,
        val duration: Long = 0
    ) : PlayerState()


    object Stopped : PlayerState()

    data class Error(
        val contentId: String?,
        val message: String
    ) : PlayerState()
}

package com.example.readingapp.core.tts

import java.text.BreakIterator
import java.util.Locale

/**
 * Tiện ích chia nhỏ văn bản bài báo thành các đoạn để tổng hợp TTS.
 * Được tối ưu hóa cho độ trễ ban đầu thấp (đoạn đầu tiên rất nhỏ).
 */
object TextSplitter {

    data class SplitResult(
        val chunks: List<String>,
        val totalChunks: Int
    )

    // Chia văn bản thành danh sách các đoạn nhỏ với kích thước tăng dần
    fun split(
        text: String,
        language: String = "vi"
    ): SplitResult {
        if (text.isBlank()) return SplitResult(emptyList(), 0)

        val chunks = mutableListOf<String>()
        val sentences = getSentences(text, language)

        if (sentences.isEmpty()) return SplitResult(listOf(text), 1)

        var currentChunk = StringBuilder()
        
        fun getTargetSize(committedChunksCount: Int, language: String): Int {
            val isVietnamese = language.startsWith("vi")
            return when (committedChunksCount) {
        0 -> if (isVietnamese) 120 else 180
        1 -> if (isVietnamese) 200 else 300
        2 -> if (isVietnamese) 350 else 450
        else -> if (isVietnamese) 450 else 550
    }
}

        for (sentence in sentences) {
            val currentTarget = getTargetSize(chunks.size, language)
            if (currentChunk.isNotEmpty() && currentChunk.length + sentence.length > currentTarget) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            currentChunk.append(sentence).append(" ")
        }

        if (currentChunk.isNotEmpty()) {
            val lastStr = currentChunk.toString().trim()
            if (lastStr.length < 50 && chunks.isNotEmpty()) {
                val lastIdx = chunks.size - 1
                chunks[lastIdx] = chunks[lastIdx] + " " + lastStr
            } else {
                chunks.add(lastStr)
            }
        }

        return SplitResult(chunks, chunks.size)
    }

    // Phân tách văn bản thành danh sách các câu
    private fun getSentences(text: String, language: String): List<String> {
        val sentences = mutableListOf<String>()
        val locale = if (language.lowercase() == "en") Locale.US else Locale("vi", "VN")
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)

        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotBlank()) {
                sentences.add(sentence)
            }
            start = end
            end = iterator.next()
        }

        return sentences
    }
}

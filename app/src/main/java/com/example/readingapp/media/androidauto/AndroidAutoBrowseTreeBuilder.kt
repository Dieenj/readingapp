package com.example.readingapp.media.androidauto

import java.text.Normalizer
import java.util.*

class AndroidAutoBrowseTreeBuilder {

    companion object {
        const val SOURCE_PREFIX = "source_"
        const val SOURCE_DETAIL_PREFIX = "sourcedetail_"

        // Tạo Media ID cho một nguồn tin cụ thể (chuẩn hóa tên nguồn)
        fun buildSourceMediaId(sourceName: String): String {
            val normalized = Normalizer.normalize(sourceName, Normalizer.Form.NFD)
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .replace("[^a-zA-Z0-9]+".toRegex(), "")
                .lowercase(Locale.ROOT)
            return "$SOURCE_PREFIX$normalized"
        }

        // Tìm lại tên nguồn tin gốc từ Media ID đã chuẩn hóa
        fun resolveSourceName(mediaId: String, availableSources: Set<String>): String? {
            return availableSources.find { buildSourceMediaId(it) == mediaId }
        }

        // Tạo Media ID cho danh mục chi tiết của một nguồn tin
        fun buildSourceDetailMediaId(sourceName: String, categoryName: String): String {
            return "${SOURCE_DETAIL_PREFIX}${sourceName}__$categoryName"
        }

        // Phân tích Media ID chi tiết để lấy lại tên nguồn và danh mục
        fun parseSourceDetailMediaId(mediaId: String): Pair<String, String>? {
            if (!mediaId.startsWith(SOURCE_DETAIL_PREFIX)) return null
            val parts = mediaId.removePrefix(SOURCE_DETAIL_PREFIX).split("__")
            return if (parts.size == 2) Pair(parts[0], parts[1]) else null
        }
    }
}

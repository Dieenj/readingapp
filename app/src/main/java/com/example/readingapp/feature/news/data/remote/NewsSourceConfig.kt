package com.example.readingapp.feature.news.data.remote

import java.util.*

/**
 * Cấu hình danh sách nguồn tin tức tĩnh và các hằng số hỗ trợ.
 */
class NewsSourceConfig {

    companion object {
        val NEWS_SOURCES = mapOf(
            "VnExpress" to mapOf(
                "Thời sự" to "https://vnexpress.net/rss/thoi-su.rss",
                "Thế giới" to "https://vnexpress.net/rss/the-gioi.rss",
                "Kinh doanh" to "https://vnexpress.net/rss/kinh-doanh.rss",
                "Giải trí" to "https://vnexpress.net/rss/giai-tri.rss",
                "Thể thao" to "https://vnexpress.net/rss/the-thao.rss",
                "Công nghệ" to "https://vnexpress.net/rss/khoa-hoc-cong-nghe.rss",
                "Pháp luật" to "https://vnexpress.net/rss/phap-luat.rss",
                "Sức khỏe" to "https://vnexpress.net/rss/suc-khoe.rss",
                "Giáo dục" to "https://vnexpress.net/rss/giao-duc.rss",
                "Du lịch" to "https://vnexpress.net/rss/du-lich.rss",
                "Xe" to "https://vnexpress.net/rss/oto-xe-may.rss"
            ),
            "Tuổi Trẻ" to mapOf(
                "Tin mới nhất" to "https://tuoitre.vn/rss/tin-moi-nhat.rss",
                "Thời sự" to "https://tuoitre.vn/rss/thoi-su.rss",
                "Thế giới" to "https://tuoitre.vn/rss/the-gioi.rss",
                "Kinh doanh" to "https://tuoitre.vn/rss/kinh-doanh.rss",
                "Giải trí" to "https://tuoitre.vn/rss/giai-tri.rss",
                "Thể thao" to "https://tuoitre.vn/rss/the-thao.rss",
                "Công nghệ" to "https://tuoitre.vn/rss/nhip-song-so.rss",
                "Pháp luật" to "https://tuoitre.vn/rss/phap-luat.rss",
                "Sức khỏe" to "https://tuoitre.vn/rss/suc-khoe.rss",
                "Giáo dục" to "https://tuoitre.vn/rss/giao-duc.rss"
            ),
            "Thanh Niên" to mapOf(
                "Tin mới nhất" to "https://thanhnien.vn/rss/home.rss",
                "Thời sự" to "https://thanhnien.vn/rss/thoi-su.rss",
                "Thế giới" to "https://thanhnien.vn/rss/the-gioi.rss",
                "Kinh tế" to "https://thanhnien.vn/rss/kinh-te.rss",
                "Giải trí" to "https://thanhnien.vn/rss/giai-tri.rss",
                "Thể thao" to "https://thanhnien.vn/rss/the-thao.rss",
                "Công nghệ" to "https://thanhnien.vn/rss/cong-nghe.rss",
                "Sức khỏe" to "https://thanhnien.vn/rss/suc-khoe.rss"
            ),
            "Dân Trí" to mapOf(
                "Trang Chủ" to "https://dantri.com.vn/rss/home.rss",
                "Thời sự" to "https://dantri.com.vn/rss/thoi-su.rss",
                "Thế giới" to "https://dantri.com.vn/rss/the-gioi.rss",
                "Kinh doanh" to "https://dantri.com.vn/rss/kinh-doanh.rss",
                "Giải trí" to "https://dantri.com.vn/rss/giai-tri.rss",
                "Thể thao" to "https://dantri.com.vn/rss/the-thao.rss",
                "Công nghệ" to "https://dantri.com.vn/rss/cong-nghe.rss",
                "Sức khỏe" to "https://dantri.com.vn/rss/suc-khoe.rss"
            ),
            "The Guardian" to mapOf(
                "News" to "https://www.theguardian.com/international/rss",
                "Opinion" to "https://www.theguardian.com/uk/commentisfree/rss",
                "Sport" to "https://www.theguardian.com/uk/sport/rss",
                "Culture" to "https://www.theguardian.com/culture/rss",
                "Lifestyle" to "https://www.theguardian.com/uk/lifeandstyle/rss"
            )
        )

        val URL_TO_SOURCE = mapOf(
            "vnexpress" to "VnExpress",
            "tuoitre" to "Tuổi Trẻ",
            "thanhnien" to "Thanh Niên",
            "dantri" to "Dân Trí",
            "theguardian" to "The Guardian"
        )

        private val VIETNAMESE_SOURCES = setOf("VnExpress", "Tuổi Trẻ", "Thanh Niên", "Dân Trí")
        val SOURCES_NEEDING_OG_IMAGE = setOf("The Guardian")

        fun getSourceLanguage(sourceName: String): String =
            if (sourceName in VIETNAMESE_SOURCES) "vi" else "en"
    }
}

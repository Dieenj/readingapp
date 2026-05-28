package com.example.readingapp.media.playback.playlist

import com.example.readingapp.core.model.ReadableContentData

interface PlaylistProvider {
    suspend fun getItemById(id: String): ReadableContentData?
    suspend fun getNextItem(currentId: String?): ReadableContentData?
    suspend fun getPreviousItem(currentId: String?): ReadableContentData?
    suspend fun getNextItems(currentId: String?, count: Int): List<ReadableContentData>
    suspend fun markAsRead(id: String)
}

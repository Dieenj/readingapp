package com.example.readingapp.core.datastore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "NewsReaderPrefs"
        private const val KEY_LAST_SOURCE = "last_source"
        private const val KEY_LAST_CATEGORY = "last_category"
        
        @Volatile
        private var instance: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _currentSource = MutableStateFlow<String?>(prefs.getString(KEY_LAST_SOURCE, null))
    val currentSource: StateFlow<String?> = _currentSource.asStateFlow()

    private val _currentCategory = MutableStateFlow<String?>(prefs.getString(KEY_LAST_CATEGORY, null))
    val currentCategory: StateFlow<String?> = _currentCategory.asStateFlow()

    fun saveSourceAndCategory(source: String, category: String) {
        prefs.edit().apply {
            putString(KEY_LAST_SOURCE, source)
            putString(KEY_LAST_CATEGORY, category)
            apply()
        }
        _currentSource.value = source
        _currentCategory.value = category
    }

    fun getSource(): String? = prefs.getString(KEY_LAST_SOURCE, null)
    
    fun getCategory(): String? = prefs.getString(KEY_LAST_CATEGORY, null)
}

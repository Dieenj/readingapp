package com.example.readingapp.feature.email.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [EmailEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(EmailConverters::class)
abstract class EmailDatabase : RoomDatabase() {

    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var INSTANCE: EmailDatabase? = null

        fun getDatabase(context: Context): EmailDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmailDatabase::class.java,
                    "email_database"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onOpen(db)
                            try {
                                db.execSQL("UPDATE OR REPLACE emails SET emailId = replace(replace(emailId, '<', ''), '>', '') WHERE emailId LIKE '<%>'")
                            } catch (e: Exception) {
                                android.util.Log.e("EmailDatabase", "Failed to auto-clean legacy emailIds", e)
                            }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

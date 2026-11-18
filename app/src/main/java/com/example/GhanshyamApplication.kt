package com.example
 
import android.app.Application
import androidx.work.Configuration
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
 
class GhanshyamApplication : Application(), Configuration.Provider {
 
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
 
    val repository: AppRepository by lazy {
        AppRepository(this, database)
    }

    override fun onCreate() {
        super.onCreate()
        // Save any crash to a file so the next start can show it (see CrashLog).
        CrashLog.install(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

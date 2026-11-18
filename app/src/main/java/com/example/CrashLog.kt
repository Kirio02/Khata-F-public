package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Keeps the last crash in a small private text file, so the next app start can show it
 * and the owner can send it for fixing. There is no computer or logcat in the shop,
 * so without this a crash leaves nothing to work from.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 6000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val header = Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n"
                val trace = Log.getStackTraceString(throwable)
                File(appContext.filesDir, FILE_NAME).writeText((header + trace).take(MAX_CHARS))
            } catch (e: Throwable) {
                // Saving the report must never get in the way of the normal crash handling.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                throwable.printStackTrace()
            }
        }
    }

    /** The saved crash text, or null when the last run ended normally. */
    fun read(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Throwable) {
            // Nothing useful to do if the file cannot be removed.
        }
    }
}

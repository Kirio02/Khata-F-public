package com.example.ui.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import java.util.concurrent.TimeUnit

class OrphanCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "orphan_attachment_cleanup_work"

        fun scheduleWeeklyCleanup(context: Context) {
            val workManager = try {
                WorkManager.getInstance(context)
            } catch (e: Throwable) {
                try {
                    WorkManager.initialize(
                        context.applicationContext,
                        Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()
                    )
                    WorkManager.getInstance(context.applicationContext)
                } catch (t: Throwable) {
                    null
                }
            } ?: return

            val request = PeriodicWorkRequestBuilder<OrphanCleanupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val allAttachments = db.attachmentDao().getAllList()
            val validPaths = HashSet<String>()
            allAttachments.forEach { att ->
                if (att.filePath.isNotBlank()) validPaths.add(att.filePath)
                if (att.thumbPath.isNotBlank()) validPaths.add(att.thumbPath)
            }
            val deleted = AttachmentManager.cleanupOrphanFiles(applicationContext, validPaths)
            Log.i("OrphanCleanupWorker", "Weekly orphan cleanup finished. Removed $deleted unreferenced files.")
            Result.success()
        } catch (e: Exception) {
            Log.e("OrphanCleanupWorker", "Orphan cleanup failed: ${e.message}", e)
            Result.retry()
        }
    }
}

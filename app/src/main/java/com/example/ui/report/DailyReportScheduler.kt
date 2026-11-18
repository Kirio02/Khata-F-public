package com.example.ui.report

import android.content.Context
import androidx.work.*
import com.example.ui.security.SecureSettingsManager
import java.util.*
import java.util.concurrent.TimeUnit

object DailyReportScheduler {

    private fun getWorkManager(context: Context): WorkManager? {
        return try {
            WorkManager.getInstance(context)
        } catch (e: Throwable) {
            try {
                WorkManager.initialize(
                    context.applicationContext,
                    Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
                )
                WorkManager.getInstance(context.applicationContext)
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun scheduleDailyReport(context: Context) {
        val isEnabled = SecureSettingsManager.isDailyReportEnabled(context)
        val workManager = getWorkManager(context) ?: return

        if (!isEnabled) {
            workManager.cancelUniqueWork(DailyReportWorker.UNIQUE_WORK_NAME)
            return
        }

        val (targetHour, targetMinute) = SecureSettingsManager.getDailyReportTime(context)

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val periodicWorkRequest = PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            DailyReportWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }

    fun triggerImmediateReport(context: Context) {
        val workManager = getWorkManager(context) ?: return
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<DailyReportWorker>()
            .build()
        workManager.enqueue(oneTimeWorkRequest)
    }
}

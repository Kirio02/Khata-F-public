package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.ui.util.LanguageStrings
import com.example.ui.GhanshyamApp
import com.example.ui.report.DailyReportScheduler
import com.example.ui.security.AppLockManager
import com.example.ui.security.LockScreen
import com.example.ui.theme.MyApplicationTheme

import android.view.WindowManager
import com.example.ui.util.OrphanCleanupWorker

class MainActivity : FragmentActivity() {

    private lateinit var appLockManager: AppLockManager
    private val isLockedState = mutableStateOf(false)
    private val pendingAiReport = mutableStateOf<String?>(null)

    fun applyFlagSecure(enable: Boolean) {
        if (enable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appLockManager = AppLockManager(this)
        val app = application as GhanshyamApplication
        val repository = app.repository

        // Schedule WorkManager periodic jobs: daily 8:30 PM report & weekly orphan attachment cleanup
        DailyReportScheduler.scheduleDailyReport(this)
        OrphanCleanupWorker.scheduleWeeklyCleanup(this)

        // Apply FLAG_SECURE if enabled
        applyFlagSecure(appLockManager.isBlockScreenshotsEnabled())

        handleReportIntent(intent)

        if (appLockManager.hasPin()) {
            isLockedState.value = true
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isLocked by isLockedState
                    val initialReport by pendingAiReport
                    val storeProfile by repository.storeProfile.collectAsState()

                    if (isLocked) {
                        LockScreen(
                            appLockManager = appLockManager,
                            storeName = storeProfile.storeName,
                            onUnlocked = {
                                isLockedState.value = false
                            }
                        )
                    } else {
                        GhanshyamApp(
                            repository = repository,
                            appLockManager = appLockManager,
                            initialAiReport = initialReport,
                            onAiReportConsumed = { pendingAiReport.value = null }
                        )
                    }

                    // If the app crashed last time, show the saved error once so it can be sent for fixing.
                    var lastCrash by remember { mutableStateOf(CrashLog.read(this@MainActivity)) }
                    val crashText = lastCrash
                    if (!isLocked && crashText != null) {
                        val crashLang by repository.language.collectAsState()
                        AlertDialog(
                            onDismissRequest = { },
                            title = { Text(text = LanguageStrings.get("crash_report_title", crashLang)) },
                            text = {
                                Column {
                                    Text(text = LanguageStrings.get("crash_report_hint", crashLang))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = crashText.take(300), fontSize = 11.sp)
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    try {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, crashText)
                                        }
                                        startActivity(Intent.createChooser(send, null))
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Could not share crash report", e)
                                    }
                                }) {
                                    Text(text = LanguageStrings.get("btn_share", crashLang))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    CrashLog.clear(this@MainActivity)
                                    lastCrash = null
                                }) {
                                    Text(text = LanguageStrings.get("btn_close", crashLang))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReportIntent(intent)
    }

    private fun handleReportIntent(intent: Intent?) {
        if (intent != null) {
            val report = intent.getStringExtra("DAILY_REPORT_CONTENT")
            if (!report.isNullOrBlank()) {
                pendingAiReport.value = report
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyFlagSecure(appLockManager.isBlockScreenshotsEnabled())
        if (appLockManager.shouldLock()) {
            isLockedState.value = true
        }
    }


    override fun onStop() {
        super.onStop()
        appLockManager.recordBackground()
    }
}

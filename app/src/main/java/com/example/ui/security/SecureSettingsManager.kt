package com.example.ui.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig

object SecureSettingsManager {

    private const val PREFS_FILE = "encrypted_owner_settings"
    private const val KEY_REPORT_HOUR = "daily_report_hour"
    private const val KEY_REPORT_MINUTE = "daily_report_minute"
    private const val KEY_REPORT_ENABLED = "daily_report_enabled"
    private const val KEY_GST_BILLS_ENABLED = "gst_bills_enabled"
    private const val KEY_NATURAL_VOICE = "ai_natural_voice_online"
    private const val KEY_POOR_VOICE_HINT_SHOWN = "tts_poor_voice_hint_shown"

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for environments where MasterKey is unsupported (e.g. some Robolectric configs)
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    fun isNaturalVoiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NATURAL_VOICE, false)
    }

    fun setNaturalVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NATURAL_VOICE, enabled).commit()
    }

    fun isPoorVoiceHintShown(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_POOR_VOICE_HINT_SHOWN, false)
    }

    fun setPoorVoiceHintShown(context: Context, shown: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_POOR_VOICE_HINT_SHOWN, shown).commit()
    }

    fun isGstBillsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GST_BILLS_ENABLED, false)
    }

    fun setGstBillsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GST_BILLS_ENABLED, enabled).commit()
    }

    fun getDailyReportTime(context: Context): Pair<Int, Int> {
        val hour = getPrefs(context).getInt(KEY_REPORT_HOUR, 20) // 8 PM
        val minute = getPrefs(context).getInt(KEY_REPORT_MINUTE, 30) // 30 mins
        return Pair(hour, minute)
    }

    fun setDailyReportTime(context: Context, hour: Int, minute: Int) {
        getPrefs(context).edit()
            .putInt(KEY_REPORT_HOUR, hour)
            .putInt(KEY_REPORT_MINUTE, minute)
            .commit()
    }

    fun isDailyReportEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REPORT_ENABLED, true)
    }

    fun setDailyReportEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REPORT_ENABLED, enabled).commit()
    }
}

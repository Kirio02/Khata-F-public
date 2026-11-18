package com.example.ui.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object SecureSettingsManager {

    private const val PREFS_FILE = "encrypted_owner_settings"
    private const val KEY_GEMINI_API_KEY = "owner_gemini_api_key"
    private const val KEY_REPORT_HOUR = "daily_report_hour"
    private const val KEY_REPORT_MINUTE = "daily_report_minute"
    private const val KEY_REPORT_ENABLED = "daily_report_enabled"
    private const val KEY_GST_BILLS_ENABLED = "gst_bills_enabled"
    private const val KEY_SPEAK_REPLIES = "ai_speak_replies"
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

    fun getGeminiApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
    }

    fun setGeminiApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_API_KEY, key.trim()).commit()
    }

    fun isSpeakRepliesEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SPEAK_REPLIES, false)
    }

    fun setSpeakRepliesEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPEAK_REPLIES, enabled).commit()
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

    suspend fun testGeminiApiKey(apiKey: String): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext Pair(false, "Invalid API key")
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${com.example.ui.ai.GhanshyamAiEngine.GEMINI_MODEL}:generateContent"
        val reqBody = org.json.JSONObject().apply {
            put("contents", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", "ping")))
            }))
        }.toString()

        val requestBody = reqBody.toRequestBody("application/json".toMediaType())

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", trimmed)
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Pair(true, "Key is valid and working!")
            } else {
                val reason = when {
                    code == 404 || body.contains("NOT_FOUND", ignoreCase = true) -> "Model not found"
                    code == 429 || body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || body.contains("quota", ignoreCase = true) -> "Quota exceeded"
                    code == 400 || code == 403 || body.contains("API_KEY_INVALID", ignoreCase = true) || body.contains("PERMISSION_DENIED", ignoreCase = true) -> "Invalid API key"
                    else -> "Invalid API key"
                }
                Pair(false, reason)
            }
        } catch (e: java.net.UnknownHostException) {
            Pair(false, "No internet connection")
        } catch (e: java.io.IOException) {
            Pair(false, "No internet connection")
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Invalid API key")
        }
    }
}

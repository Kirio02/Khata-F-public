package com.example.ui.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom

class AppLockManager(private val context: Context) {

    private val encryptedPrefs: SharedPreferences = try {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "encrypted_app_lock_prefs",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("encrypted_app_lock_prefs", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_lock_security_prefs",
        Context.MODE_PRIVATE
    )

    init {
        // Migrate existing PIN hash and salt from standard SharedPreferences to EncryptedSharedPreferences
        val oldHash = prefs.getString(KEY_PIN_HASH, "") ?: ""
        val oldSalt = prefs.getString(KEY_PIN_SALT, "") ?: ""
        if (oldHash.isNotBlank() && (encryptedPrefs.getString(KEY_PIN_HASH, "") ?: "").isBlank()) {
            encryptedPrefs.edit()
                .putString(KEY_PIN_HASH, oldHash)
                .putString(KEY_PIN_SALT, oldSalt)
                .commit()
            prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).commit()
        }
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_LAST_BACKGROUND = "last_background_timestamp"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_LOCKOUT_ROUND = "pin_lockout_round"
        const val LOCK_TIMEOUT_MS = 60_000L // 1 minute
    }

    private var isSessionUnlocked = false

    fun isBlockScreenshotsEnabled(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false)
    }

    fun setBlockScreenshotsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, enabled).commit()
    }

    fun hasPin(): Boolean {
        val hash = encryptedPrefs.getString(KEY_PIN_HASH, "") ?: ""
        return hash.isNotBlank()
    }

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        encryptedPrefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash)
            .commit()
        resetLockout()
    }

    fun getRemainingLockoutSeconds(): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) (until - now + 999) / 1000 else 0L
    }

    fun isLockedOut(): Boolean {
        return getRemainingLockoutSeconds() > 0L
    }

    private fun resetLockout() {
        prefs.edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .remove(KEY_LOCKOUT_ROUND)
            .commit()
    }

    fun recordFailedAttempt(): Long {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        var round = prefs.getInt(KEY_LOCKOUT_ROUND, 0)
        var lockSeconds = 0L

        if (attempts >= 5) {
            // After 5 wrong PINs lock for 30s, doubling each round up to 15 mins (900s)
            val baseSeconds = 30L
            val multiplier = 1L shl round.coerceIn(0, 10)
            lockSeconds = (baseSeconds * multiplier).coerceAtMost(900L) // 15 mins max
            round++
            val lockoutUntil = System.currentTimeMillis() + (lockSeconds * 1000L)
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, attempts)
                .putInt(KEY_LOCKOUT_ROUND, round)
                .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                .commit()
        } else {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).commit()
        }
        return lockSeconds
    }

    fun verifyPin(pin: String): Boolean {
        if (isLockedOut()) return false
        val savedSalt = encryptedPrefs.getString(KEY_PIN_SALT, "") ?: ""
        val savedHash = encryptedPrefs.getString(KEY_PIN_HASH, "") ?: ""
        if (savedSalt.isBlank() || savedHash.isBlank()) return false
        val computedHash = hashPin(pin, savedSalt)
        val matches = computedHash.equals(savedHash, ignoreCase = true)
        if (matches) {
            resetLockout()
            onSessionUnlocked()
        } else {
            recordFailedAttempt()
        }
        return matches
    }

    fun removePin() {
        encryptedPrefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .commit()
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_LAST_BACKGROUND)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .remove(KEY_LOCKOUT_ROUND)
            .commit()
        isSessionUnlocked = false
    }

    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true) && canAuthenticateWithBiometrics()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).commit()
    }

    fun canAuthenticateWithBiometrics(): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            val canAuth = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            canAuth == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    fun recordBackground() {
        if (hasPin() && isSessionUnlocked) {
            prefs.edit().putLong(KEY_LAST_BACKGROUND, System.currentTimeMillis()).commit()
        }
    }

    fun shouldLock(): Boolean {
        if (!hasPin()) return false
        if (!isSessionUnlocked) return true

        val lastBg = try {
            prefs.getLong(KEY_LAST_BACKGROUND, 0L)
        } catch (e: Exception) {
            prefs.getString(KEY_LAST_BACKGROUND, null)?.toLongOrNull() ?: 0L
        }
        if (lastBg > 0L) {
            val diff = System.currentTimeMillis() - lastBg
            if (diff >= LOCK_TIMEOUT_MS) {
                isSessionUnlocked = false
                return true
            }
        }
        return false
    }

    fun onSessionUnlocked() {
        isSessionUnlocked = true
        prefs.edit().putLong(KEY_LAST_BACKGROUND, 0L).commit()
    }

    fun lockNow() {
        isSessionUnlocked = false
        prefs.edit().putLong(KEY_LAST_BACKGROUND, System.currentTimeMillis()).commit()
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canAuthenticateWithBiometrics()) {
            onError("Biometrics not available")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSessionUnlocked()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If user clicked negative button or dismissed, error is delivered
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Still listening for a valid touch
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Ghanshyam Billing")
            .setSubtitle("Use your fingerprint to continue")
            .setNegativeButtonText("Use PIN")
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError(e.message ?: "Authentication error")
        }
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt.joinToString("") { "%02x".format(it) }
    }

    private fun hashPin(pin: String, saltHex: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(saltHex.toByteArray(Charsets.UTF_8))
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

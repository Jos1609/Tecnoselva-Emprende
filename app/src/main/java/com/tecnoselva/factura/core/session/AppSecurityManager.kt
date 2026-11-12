package com.tecnoselva.factura.core.session

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

class AppSecurityManager(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        // Clear a stale biometric-only configuration so it cannot bypass the PIN requirement.
        if (preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false) && !preferences.getBoolean(KEY_PIN_ENABLED, false)) {
            preferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
        }
    }

    val pinEnabled: Boolean
        get() = preferences.getBoolean(KEY_PIN_ENABLED, false)

    val biometricEnabled: Boolean
        get() = preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    val hasAnyLock: Boolean
        get() = pinEnabled || biometricEnabled

    fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        preferences.edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash(salt, pin))
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()
    }

    fun enableBiometric(): Boolean {
        if (!pinEnabled) return false
        preferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, true).apply()
        return true
    }

    fun disableBiometric() {
        preferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = preferences.getString(KEY_PIN_SALT, null)?.fromHex() ?: return false
        val expected = preferences.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())
    }

    fun clearPin() {
        preferences.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_PIN_ENABLED, false)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .apply()
    }

    private fun hash(salt: ByteArray, pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(salt + pin.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PREFERENCES_NAME = "tecnoselva_app_security"
        const val KEY_PIN_ENABLED = "pin_enabled"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}

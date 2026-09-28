package com.autoexpensetracker.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import android.util.Base64

/**
 * Generates a random 256-bit passphrase for the SQLCipher database on first
 * launch, and stores it inside EncryptedSharedPreferences, whose own key is
 * held in the Android Keystore (hardware-backed / StrongBox where available).
 *
 * The raw DB passphrase is never hardcoded, never logged, and never leaves
 * the device. See REQUIREMENTS.md Security ยง1.
 */
object KeystoreHelper {

    private const val PREFS_NAME = "secure_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase"

    fun getOrCreateDbPassphrase(context: Context): ByteArray {
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        val newKey = ByteArray(32) // 256-bit
        SecureRandom().nextBytes(newKey)
        // commit(), not apply(): apply() flushes to disk asynchronously. If
        // the process died in that window, the database would already be
        // encrypted with a key that was never persisted, and every later
        // launch would generate a different key and fail to open the file,
        // permanently locking the user out of their own data. commit()
        // blocks until the write is durable, and a failed write must abort
        // before the caller creates a database with this key.
        val saved = prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(newKey, Base64.NO_WRAP))
            .commit()
        check(saved) { "Could not persist the database passphrase" }
        return newKey
    }

    private fun encryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}

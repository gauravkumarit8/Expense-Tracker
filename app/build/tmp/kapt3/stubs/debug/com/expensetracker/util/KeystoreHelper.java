package com.expensetracker.util;

/**
 * Generates a random 256-bit passphrase for the SQLCipher database on first
 * launch, and stores it inside EncryptedSharedPreferences, whose own key is
 * held in the Android Keystore (hardware-backed / StrongBox where available).
 *
 * The raw DB passphrase is never hardcoded, never logged, and never leaves
 * the device. See REQUIREMENTS.md Security ยง1.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000&\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0012\n\u0000\b\u00c6\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u0010\u0010\u0006\u001a\u00020\u00072\u0006\u0010\b\u001a\u00020\tH\u0002J\u000e\u0010\n\u001a\u00020\u000b2\u0006\u0010\b\u001a\u00020\tR\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\f"}, d2 = {"Lcom/expensetracker/util/KeystoreHelper;", "", "()V", "KEY_DB_PASSPHRASE", "", "PREFS_NAME", "encryptedPrefs", "Landroid/content/SharedPreferences;", "context", "Landroid/content/Context;", "getOrCreateDbPassphrase", "", "app_debug"})
public final class KeystoreHelper {
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String PREFS_NAME = "secure_prefs";
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String KEY_DB_PASSPHRASE = "db_passphrase";
    @org.jetbrains.annotations.NotNull()
    public static final com.expensetracker.util.KeystoreHelper INSTANCE = null;
    
    private KeystoreHelper() {
        super();
    }
    
    @org.jetbrains.annotations.NotNull()
    public final byte[] getOrCreateDbPassphrase(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
        return null;
    }
    
    private final android.content.SharedPreferences encryptedPrefs(android.content.Context context) {
        return null;
    }
}
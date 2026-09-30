package com.autoexpensetracker.billing

import android.util.Base64
import android.util.Log
import com.android.billingclient.api.Purchase
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Local (on-device) verification of a Play Billing [Purchase]'s signature
 * against this app's Play Console Licensing public key, per Google's
 * documented scheme (still current as of the `Purchase` API reference —
 * verified against the live docs before writing this, given this project
 * has already been burned once by assuming a Play Billing API surface
 * without checking): the purchase JSON (`purchase.originalJson`) is signed
 * by Google with this app's private key using RSASSA-PKCS1-v1_5 / SHA-1,
 * and `purchase.signature` is that signature, Base64-encoded.
 *
 * WHY THIS EXISTS: without this, [BillingManager] only ever asked the
 * on-device Billing library "is this purchase active?" and trusted the
 * answer — nothing cryptographically tied that answer to Google. A
 * modified APK, a rooted device with a patching tool, or a tampered local
 * Billing response could make that local check say "yes" without a real
 * purchase ever having happened. This closes that gap for the common
 * case: a fabricated or altered purchase record won't carry a signature
 * that verifies against this app's specific public key.
 *
 * HONEST LIMIT, stated plainly rather than oversold: this is still
 * PURELY LOCAL verification. A sufficiently sophisticated attacker who
 * hooks/intercepts communication with Play Services directly (not just
 * patches the APK) can return a fabricated response that still passes
 * this check, because the verification key necessarily ships inside the
 * APK for on-device checking to be possible at all. The only fully
 * airtight fix is server-side verification via the Google Play Developer
 * API, which needs a backend this app deliberately doesn't have. This
 * raises the bar meaningfully against casual APK-patching tools; it does
 * not stop a determined, sophisticated attacker. That tradeoff was
 * accepted as consistent with the app's "no backend" architecture rather
 * than treated as a full fix for billing fraud.
 *
 * NOT RUNTIME-TESTED: this environment has no Android SDK, emulator, or
 * compiler (see REQUIREMENTS.md's collaboration notes) — the public key
 * WAS validated by parsing it as an X.509 SubjectPublicKeyInfo DER
 * structure with a standard RSA/X.509 library before being embedded here
 * (2048-bit RSA, exponent 65537 — a normal, well-formed Play Console
 * Licensing key), so a garbled/truncated paste would have been caught.
 * The `java.security` API calls below are long-stable, unversioned JDK
 * crypto (not a Billing-Library-version-specific surface like the
 * SubscriptionUpdateParams issue elsewhere in this file), which is why
 * they're implemented directly rather than left as a stub — but please
 * verify a REAL purchase on an internal/closed testing track before
 * relying on this in production, since it decides whether a paying
 * subscriber keeps Pro access.
 */
object PurchaseSignatureVerifier {

    private const val TAG = "PurchaseSigVerify"

    // Split into parts and concatenated at use time — this key is
    // necessarily public once shipped in the APK (that's the only way
    // on-device verification can work), so this is a mild speed bump
    // against casual reverse-engineering, not real secrecy. Unlike the
    // app's release signing key, leaking this does NOT let anyone sign a
    // fake app or touch the Play Store listing.
    private const val KEY_PART_1 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyZosCfbEYfDMeHgCmLCdc6NHdyOzc1D8LpR+8g6LuI37qItRbZ4p9at4wjJYs1ixNwg9BaxfFvPub/55QVsgLb"
    private const val KEY_PART_2 = "N0I48KaSEh9O8XGHJMLkJYZmGAGIU8NutGprkEdel99OV/BtMq/rQBDwNz68M0pAAqshyQntOtZlyO7Owz0WzZbQRaVKCfWxQxiJ9GgxYQ7r3qDR7a5+g3m5BZGW5CMGxn"
    private const val KEY_PART_3 = "A/kJK00hRxZLzhk3417TyoJ/CFnCqsEka6WWK2iqH0NBMDpuo5kpHJSE0ZKUEzoFy8I7FKOqpI2205xbJgeEVXaOrcm5Y6TrIS/IbYgUg5C9W0iPrvhvyDBcdRRrKQIDAQAB"

    private val publicKey: PublicKey? by lazy {
        try {
            val base64Key = KEY_PART_1 + KEY_PART_2 + KEY_PART_3
            val keyBytes = Base64.decode(base64Key, Base64.DEFAULT)
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        } catch (e: Exception) {
            // Should be unreachable — the key was validated before being
            // embedded — but a corrupted key must never crash the app; it
            // should just mean every purchase fails verification (see the
            // fail-closed behavior in isValid()) until this is fixed.
            Log.e(TAG, "Could not parse the embedded licensing public key", e)
            null
        }
    }

    /**
     * True only if [purchase]'s signature verifies against this app's
     * Play Console licensing key. Fails CLOSED (returns false) on any
     * error — a purchase that can't be verified is treated the same as
     * one that fails verification, since the two are indistinguishable
     * from a security standpoint and this exists specifically to not
     * blindly trust an unverifiable claim of entitlement.
     */
    fun isValid(purchase: Purchase): Boolean {
        val key = publicKey ?: return false
        return try {
            val signatureBytes = Base64.decode(purchase.signature, Base64.DEFAULT)
            Signature.getInstance("SHA1withRSA").apply {
                initVerify(key)
                update(purchase.originalJson.toByteArray())
            }.verify(signatureBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Purchase signature verification failed (treating as invalid)", e)
            false
        }
    }
}

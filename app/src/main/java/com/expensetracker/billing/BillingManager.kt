package com.autoexpensetracker.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side-only subscription entitlement checking — no backend of ours.
 * Google Play is the system of record for "is this user currently
 * subscribed"; we query it directly via BillingClient rather than
 * maintaining our own subscriber database.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_MONTHLY = "expense_tracker_pro_monthly"
        const val PRODUCT_ID_YEARLY = "expense_tracker_pro_yearly"
    }

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _productDetails = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetails: StateFlow<List<ProductDetails>> = _productDetails.asStateFlow()

    // 2026-09-08: tracks the user's current active subscription purchase
    // (not just whether they're Pro) — needed to (a) show which plan
    // they're actually on, and (b) supply the old purchase token required
    // to switch plans below.
    private val _activePurchase = MutableStateFlow<Purchase?>(null)
    val activePurchase: StateFlow<Purchase?> = _activePurchase.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    // Prevents overlapping startConnection() calls (resume + refresh +
    // disconnect callback can all ask for a connection at once).
    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun startConnection(onReady: () -> Unit = {}) {
        if (billingClient.isReady) {
            refreshEntitlement()
            onReady()
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting.set(false)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlement()
                    queryProducts()
                    onReady()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // NOT automatic: BillingClient does not reconnect by itself
                // (the old comment here claimed it did). Clear the flag so
                // the next refreshEntitlement() - called on every app
                // resume - starts a fresh connection.
                connecting.set(false)
            }
        })
    }

    /** Re-checks current entitlement against Google Play. */
    fun refreshEntitlement() {
        // Not connected (never connected, or the Play service dropped us):
        // queryPurchasesAsync would just fail with SERVICE_DISCONNECTED and
        // leave a stale entitlement. Reconnect instead; a successful setup
        // calls back into this method.
        if (!billingClient.isReady) {
            startConnection()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync

            // Signature-checked against this app's Play Console licensing
            // key (see PurchaseSignatureVerifier's doc comment for what
            // this does and does not protect against) — previously any
            // purchase the Billing library reported as PURCHASED was
            // trusted outright, with nothing tying that local answer back
            // to Google cryptographically.
            val activeSub = purchases.firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    (it.products.contains(PRODUCT_ID_MONTHLY) || it.products.contains(PRODUCT_ID_YEARLY)) &&
                    PurchaseSignatureVerifier.isValid(it)
            }

            // Distinguished from "no purchase at all" purely for
            // diagnostics: this specific combination (Play says PURCHASED,
            // but the signature doesn't verify) is the one worth knowing
            // about if it ever happens on a real device, since it's either
            // a tampered purchase or a bug in this verification code.
            val unverifiedPurchase = purchases.firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    (it.products.contains(PRODUCT_ID_MONTHLY) || it.products.contains(PRODUCT_ID_YEARLY)) &&
                    !PurchaseSignatureVerifier.isValid(it)
            }
            if (unverifiedPurchase != null) {
                Log.w(TAG, "A PURCHASED subscription failed signature verification and was NOT granted Pro access")
            }

            _isPro.value = activeSub != null
            _activePurchase.value = activeSub

            // Purchases must be acknowledged within 3 days or Google
            // Play automatically refunds them — silently discarding the
            // result here (as this previously did) means a failed
            // acknowledgment (network blip, transient billing service
            // issue, anything) would go completely unnoticed until a
            // paying customer's subscription just vanished 3 days later
            // with no error trail to explain why. Logging failures at
            // minimum surfaces it in crash/logcat monitoring; a single
            // retry covers the common transient case without adding
            // real complexity (queryActiveSubscription already re-runs
            // on every app foreground per its call sites, so a
            // still-unacknowledged purchase gets another attempt soon
            // regardless — this retry just doesn't wait for that).
            if (activeSub != null && !activeSub.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(activeSub.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(ackParams) { ackResult ->
                    if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(
                            "BillingManager",
                            "Purchase acknowledgment failed (code=${ackResult.responseCode}, ${ackResult.debugMessage}) — retrying once"
                        )
                        billingClient.acknowledgePurchase(ackParams) { retryResult ->
                            if (retryResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                Log.e(
                                    "BillingManager",
                                    "Purchase acknowledgment retry also failed (code=${retryResult.responseCode}, ${retryResult.debugMessage}) — will be retried again on next subscription check"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun queryProducts() {
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()

        billingClient.queryProductDetailsAsync(params) { result, queryProductDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = queryProductDetailsResult.productDetailsList ?: emptyList()
            }
        }
    }

    /** Launches the Play purchase flow for a brand-new subscription (no existing plan to replace). */
    fun launchPurchaseFlow(activity: Activity, product: ProductDetails) {
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .setOfferToken(offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    /**
     * Switches an already-subscribed user to a different plan (monthly <->
     * yearly) — two separate subscription products in this app's Play
     * Console setup, not two base plans of one product.
     *
     * IMPORTANT VERSION NOTE (2026-09-08): this app is pinned to Play
     * Billing Library 8.0.0 (see app/build.gradle). An earlier version of
     * this method used `SubscriptionProductReplacementParams`, which was
     * only introduced in Billing Library 8.1.0 — that failed to compile
     * against 8.0.0 with "Unresolved reference" (confirmed via a real
     * `./gradlew assembleDebug` failure). Fixed here using the API that
     * actually exists at 8.0.0: `BillingFlowParams.SubscriptionUpdateParams`,
     * attached to the outer `BillingFlowParams` builder via
     * `setSubscriptionUpdateParams()` — NOT nested inside
     * `ProductDetailsParams` the way the newer 8.1+ API works. Verified
     * against Android's official reference docs (SubscriptionUpdateParams
     * .ReplacementMode, last updated 2026-05-19) and Google's own
     * "Subscription with add-ons" sample (dated 2026-06-22) before writing
     * this — not guessed a second time.
     *
     * If/when this project bumps Billing Library to 8.1.0+, the newer
     * `SubscriptionProductReplacementParams` API becomes available again
     * and either shape will keep working (8.1+ keeps this one, just
     * deprecated) — no urgency to migrate.
     *
     * WITH_TIME_PRORATION: switch takes effect immediately, and the
     * remaining value of the old plan is credited toward the new one —
     * Google's own documented default behavior, and the least surprising
     * choice for a user-initiated plan change.
     *
     * No-ops if there's no active purchase to replace — callers should
     * only offer this when [activePurchase] is non-null.
     */
    fun launchPlanChangeFlow(activity: Activity, newProduct: ProductDetails) {
        val oldPurchase = _activePurchase.value ?: run {
            Log.w(TAG, "launchPlanChangeFlow called with no active purchase to replace")
            return
        }
        val offerToken = newProduct.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(newProduct)
            .setOfferToken(offerToken)
            .build()

        val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(oldPurchase.purchaseToken)
            .setSubscriptionReplacementMode(
                BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
            )
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setSubscriptionUpdateParams(updateParams)
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    /**
     * Deep-links to Google Play's own subscription management screen —
     * the only sanctioned way to let a user cancel a subscription. Play
     * Billing deliberately does not expose a "cancel" API to third-party
     * apps at all; Google keeps cancellation centralized under the user's
     * control so apps can't make it artificially difficult. Passing the
     * active product's ID as `sku` takes the user straight to that specific
     * subscription's management page rather than their full subscriptions
     * list.
     */
    fun manageSubscriptionsIntent(): Intent {
        val activeProductId = _activePurchase.value?.products?.firstOrNull()
        val uri = if (activeProductId != null) {
            Uri.parse("https://play.google.com/store/account/subscriptions?sku=$activeProductId&package=${context.packageName}")
        } else {
            Uri.parse("https://play.google.com/store/account/subscriptions")
        }
        return Intent(Intent.ACTION_VIEW, uri)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        // ITEM_ALREADY_OWNED means Play already has this subscription on the
        // account but our local state didn't know (e.g. bought on another
        // device, or a previous refresh failed). Re-querying repairs it.
        if (result.responseCode == BillingClient.BillingResponseCode.OK ||
            result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        ) {
            refreshEntitlement()
        }
    }

    fun endConnection() {
        billingClient.endConnection()
    }
}
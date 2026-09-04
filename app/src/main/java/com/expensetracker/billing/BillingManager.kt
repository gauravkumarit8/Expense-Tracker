package com.autoexpensetracker.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side-only subscription entitlement checking — no backend of ours.
 * Google Play is the system of record for "is this user currently
 * subscribed"; we query it directly via BillingClient rather than
 * maintaining our own subscriber database. See REQUIREMENTS.md ยง2.17 for
 * the full reasoning and the tradeoffs of this choice vs. a full
 * server-side receipt-validation + Real-time Developer Notifications setup.
 *
 * PRODUCT_ID_MONTHLY / PRODUCT_ID_YEARLY must be created in Play Console
 * under Monetization > Products > Subscriptions with these exact IDs
 * before purchases will work. Subscriptions cannot be tested via sideloaded
 * debug builds — the app must be uploaded to at least the Internal Testing
 * track, and the testing Google account must be added as a license tester
 * in Play Console. This is a hard Google Play constraint, not a bug here.
 */
class BillingManager(context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_MONTHLY = "expense_tracker_pro_monthly"
        const val PRODUCT_ID_YEARLY = "expense_tracker_pro_yearly"
    }

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _productDetails = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetails: StateFlow<List<ProductDetails>> = _productDetails.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun startConnection(onReady: () -> Unit = {}) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlement()
                    queryProducts()
                    onReady()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // BillingClient can auto-reconnect on the next call; nothing
                // to do here beyond letting queries fail gracefully.
            }
        })
    }

    /** Re-checks current entitlement against Google Play. Call on app start
     *  and after any purchase flow completes — Play is always the source of
     *  truth, never a locally cached flag alone. */
    fun refreshEntitlement() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync

            val activeSub = purchases.firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    (it.products.contains(PRODUCT_ID_MONTHLY) || it.products.contains(PRODUCT_ID_YEARLY))
            }

            _isPro.value = activeSub != null

            // Purchases must be acknowledged within 3 days or Google
            // auto-refunds them and revokes access.
            if (activeSub != null && !activeSub.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(activeSub.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(ackParams) { }
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

        billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = productDetailsResult.productDetailsList
            }
        }
    }

    /** Launches the Play purchase flow for the given product. Call
     *  refreshEntitlement() from the PurchasesUpdatedListener callback
     *  (handled internally here) once the flow completes. */
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

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            refreshEntitlement()
        }
        // Cancelled (BillingResponseCode.USER_CANCELED) and error cases are
        // silently ignored here — the purchase UI simply doesn't change,
        // which is the correct behavior (no error dialog needed for a
        // deliberate cancel).
    }

    fun endConnection() {
        billingClient.endConnection()
    }
}
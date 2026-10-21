package com.autoexpensetracker.parser

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Category.*
import com.autoexpensetracker.parser.Categorizer
private fun cat(m: String?, t: String = "") = Categorizer.categorize(m, t)

class CategorizerTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    // known merchants still work
    check("swiggy", cat("Swiggy") == FOOD)
    check("zomato upper", cat("ZOMATO LTD") == FOOD)
    check("foodpanda compound", cat("Foodpanda") == FOOD)
    check("restaurants substring", cat("Annapurna Restaurants Pvt") == FOOD)
    check("cafe word", cat("Cafe Coffee Day") == FOOD)
    check("bigbasket", cat("BigBasket") == GROCERIES)
    check("jiomart is groceries", cat("JioMart") == GROCERIES)
    check("amazon", cat("Amazon Pay") == SHOPPING)
    check("airtel", cat("Airtel Payments Bank") == BILLS)
    check("jio word", cat("Reliance Jio") == BILLS)
    check("jiofiber", cat("JioFiber") == BILLS)
    check("vi telco word", cat("Vi Prepaid Recharge") == BILLS)
    check("vi alone", cat("Vi") == BILLS)
    check("emi word", cat("HDFC EMI") == BILLS)
    check("netflix", cat("Netflix") == ENTERTAINMENT)
    check("ola cabs", cat("Ola Cabs") == TRAVEL)
    check("uber", cat("Uber India") == TRAVEL)
    check("irctc", cat("IRCTC") == TRAVEL)
    check("apollo pharmacy", cat("Apollo Pharmacy") == HEALTH)
    check("1mg", cat("1mg Technologies") == HEALTH)
    // people and words that used to be mislabelled
    check("Ravi Kumar not bills", cat("Ravi Kumar", "Rs 500 sent to Ravi Kumar via UPI") == OTHER)
    check("Devi not bills", cat("Devi Prasad") == OTHER)
    check("Dinesh not food", cat("Dinesh Kumar") == OTHER)
    check("Coca-Cola not travel", cat("Coca-Cola Stall") == OTHER)
    check("Small not shopping", cat("Small Finance Dept") == OTHER)
    check("Lola not travel", cat("Lola Sharma") == OTHER)
    check("Nikita not toll/ola", cat("Nikita") == OTHER)
    // the Transfer problem
    check("unknown upi merchant is Other", cat("Sharma Tea Stall", "Rs.40 debited a/c XX12 and credited to sharma@ybl via UPI") == OTHER)
    check("sent to generic is Other", cat(null, "Rs.340 sent to JOHN via UPI Ref 123") == OTHER)
    check("received from generic is Other", cat(null, "Rs.340 received from JOHN via UPI") == OTHER)
    check("bare upi word is Other", cat(null, "UPI txn Rs 90") == OTHER)
    check("neft is transfer", cat(null, "Rs 50000 NEFT to ACME") == TRANSFER)
    check("imps is transfer", cat(null, "IMPS Rs 2000 debited") == TRANSFER)
    check("rtgs is transfer", cat(null, "RTGS credit of Rs 300000") == TRANSFER)
    check("fund transfer", cat(null, "Fund Transfer of Rs 10000") == TRANSFER)
    check("own account", cat(null, "Rs 1000 to own account") == TRANSFER)
    check("transfer in merchant", cat("Self Transfer") == TRANSFER)
    // priority and fallback behaviour unchanged
    check("merchant beats text", cat("Swiggy", "Rs 500 NEFT") == FOOD)
    check("text fallback finds brand", cat(null, "Rs 200 spent at SWIGGY via UPI") == FOOD)
    check("null merchant empty text", cat(null, "") == OTHER)
    check("whole word at start/end", cat("kfc") == FOOD && cat("order kfc") == FOOD && cat("kfc!") == FOOD)
    check("whole word needs boundary", cat("kfcx") == OTHER)
    check("digits are word chars", cat("1mg") == HEALTH && cat("21mg") == OTHER)
    checks.assertAll()
    }
}

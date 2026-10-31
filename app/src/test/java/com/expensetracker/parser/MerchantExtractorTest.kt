package com.autoexpensetracker.parser

import com.autoexpensetracker.Checks
import com.autoexpensetracker.data.Direction
import org.junit.Test

class MerchantExtractorTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun junkIsRecognised() {
        check("null", MerchantExtractor.isJunk(null))
        check("blank", MerchantExtractor.isJunk("  "))
        check("one char", MerchantExtractor.isJunk("a"))
        check("helpline number", MerchantExtractor.isJunk("9215676766"))
        check("toll free", MerchantExtractor.isJunk("18001035577"))
        check("reference number", MerchantExtractor.isJunk("429387461234"))
        check("block", MerchantExtractor.isJunk("BLOCK UPI"))
        check("call", MerchantExtractor.isJunk("Call 18002586161"))
        check("your account", MerchantExtractor.isJunk("your account"))
        check("symbols only", MerchantExtractor.isJunk("--"))
        check("letters glued to a reference number", MerchantExtractor.isJunk("REF429387461234"))
        check("UTR with number", MerchantExtractor.isJunk("UTR 429387461234"))
        check("short number in a name is fine", !MerchantExtractor.isJunk("Sector 21 Store"))
        check("a real name is not junk", !MerchantExtractor.isJunk("SWIGGY"))
        check("name with digits is not junk", !MerchantExtractor.isJunk("7 Eleven"))
        check("hindi name is not junk", !MerchantExtractor.isJunk("कैफ़े चाय"))
        check("vpa is not junk", !MerchantExtractor.isJunk("rahul@ybl"))
        check("blockbuster is not junk", !MerchantExtractor.isJunk("Blockbuster Video"))
        checks.assertAll()
    }

    @Test
    fun extractsFromTheFormatsBanksUse() {
        fun ex(t: String) = MerchantExtractor.extract(t)
        check("upi p2m reference", ex("Rs 500 debited. UPI/P2M/429387461234/SWIGGY. Not you?") == "SWIGGY")
        check("upi p2a reference", ex("credited. UPI/P2A/429387461234/Rahul. Axis Bank") == "Rahul")
        check("upi plain reference", ex("Info: UPI/429387461234/Swiggy. Avl Bal Rs 1,200") == "Swiggy")
        check("upi dash reference", ex("Info: UPI-429387461234-ZOMATO. Avl bal:INR 15,230.45") == "ZOMATO")
        check("two word merchant", ex("UPI/P2M/429387461234/SWIGGY INSTAMART. Avl") == "SWIGGY INSTAMART")
        check("upi id after to", ex("Sent Rs.500 from A/c to swiggy@icici on 24-10-26") == "swiggy@icici")
        check("upi id after vpa", ex("credited on 24-10-26 from VPA rahul@ybl (UPI 429387461234)") == "rahul@ybl")
        check("upi id after by", ex("Cr. to A/C XXXXXX1234 by rahul@ybl. Ref:429387461234.") == "rahul@ybl")
        check("trf to", ex("debited by 500.0 on date 24Oct26 trf to SWIGGY Refno 429387461234") == "SWIGGY")
        check("transfer from", ex("credited by Rs.2000 on 24Oct26 transfer from RAHUL Ref No 429387461234") == "RAHUL")
        check("debited then credited", ex("debited for Rs 500.00 on 24-Oct-26; SWIGGY credited. UPI:429387461234.") == "SWIGGY")
        check("towards", ex("debited towards NETFLIX using UPI Mandate. UPI Ref 429387461234") == "NETFLIX")
        check("at merchant", ex("used for a transaction of INR 1,500.00 on 24-Oct-26 at AMAZON PAY. Avl Limit: INR 98,500.00") == "AMAZON PAY")
        check("nothing to find", ex("Your a/c XX1234 balance is Rs 1,000.00") == null)
        check("a phone number is never a merchant", ex("debited. SMS BLOCK 123 to 9215676766.") == null)
        check("empty", ex("") == null)
        checks.assertAll()
    }

    @Test
    fun resolvePrefersAGoodPatternResult() {
        check("keeps a real pattern name", MerchantExtractor.resolve("Zomato", "UPI/P2M/429387461234/SWIGGY.") == "Zomato")
        check("replaces junk", MerchantExtractor.resolve("9215676766", "UPI/P2M/429387461234/SWIGGY.") == "SWIGGY")
        check("replaces null", MerchantExtractor.resolve(null, "UPI/P2M/429387461234/SWIGGY.") == "SWIGGY")
        check("null stays null when nothing is found", MerchantExtractor.resolve(null, "debited Rs 5") == null)
        checks.assertAll()
    }

    @Test
    fun directionAndLastResortAmount() {
        fun d(t: String) = TransactionParser.detectDirection(t)
        check("Dr then Cr is a debit", d("Rs.500.00 Dr. from A/C XX1234 and Cr. to swiggy@icici") == Direction.SENT)
        check("Cr alone is a credit", d("Rs.2000.00 Cr. to A/C XX1234 by rahul@ybl") == Direction.RECEIVED)
        check("a debit of", d("Your A/C XXXX1234 has a debit of INR 500.00") == Direction.SENT)
        check("a credit of", d("Your A/C XXXX1234 has a credit of INR 500.00") == Direction.RECEIVED)
        check("debit alert", d("Debit Alert: Rs 500 from XX1234") == Direction.SENT)
        check("card used for", d("Credit Card XX1234 has been used for a transaction of INR 1,500.00") == Direction.SENT)
        check("swiped", d("Card swiped for Rs 300 at STORE") == Direction.SENT)
        check("debit card is not a direction", d("Your debit card XX1234 has been blocked") == Direction.UNKNOWN)
        check("credit card is not a direction", d("Your credit card XX1234 statement is ready") == Direction.UNKNOWN)
        check("Dr inside a name is not a debit", d("Rs 500 received from DR REDDY") == Direction.RECEIVED)
        check("plain words still work", d("Rs 5 debited") == Direction.SENT && d("Rs 5 credited") == Direction.RECEIVED)
        fun a(t: String) = TransactionParser.lastResortAmount(t)
        check("amount before the word", a("Rs.500.00 Dr. from A/C XX1234") == "500.00")
        check("inr prefix", a("INR 1,200.00 debited") == "1,200.00")
        check("rupee sign", a("Paid ₹50 to Ramesh") == "50")
        check("skips the balance", a("Avl Bal Rs 25,000.00. Rs 500 debited") == "500")
        check("skips the limit", a("Avl Lmt Rs. 1,20,000. Rs 340 spent") == "340")
        check("skips the total due", a("Total due Rs 5,000 minimum Rs 250 Rs 99 debited") == "99")
        check("none", a("no money mentioned") == null)
        checks.assertAll()
    }
}

package com.autoexpensetracker.parser

import com.autoexpensetracker.data.Direction

/** One alert and what the parser should make of it. [dir] null means "not a transaction: reject". */
data class Case(
    val name: String, val sender: String, val text: String,
    val dir: Direction?, val amount: Double? = null, val merchant: String? = null, val balance: Double? = null
)

object BankAlertCorpus {
    val S = Direction.SENT
    val R = Direction.RECEIVED
    val ALL: List<Case> = listOf(
        // ---- HDFC
        Case("HDFC UPI sent", "VD-HDFCBN-P", "Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 24/10/26\nRef 429387461234\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808", S, 500.0, "SWIGGY"),
        Case("HDFC credit alert", "HDFCBK", "Credit Alert!\nRs.5,000.00 credited to HDFC Bank A/c XX1234 on 24-10-26 from VPA rahul@ybl (UPI 429387461234)", R, 5000.0, "rahul@ybl"),
        Case("HDFC update debit", "HDFCBK", "UPDATE: INR 1,200.00 debited from HDFC Bank XX1234 on 24-OCT-26. Info: UPI-429387461234-ZOMATO. Avl bal:INR 15,230.45. Not you? Call 18002586161", S, 1200.0, "ZOMATO", 15230.45),
        Case("HDFC card spend", "HDFCBK", "Rs. 340.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-10-24:12:10:35. Avl Lmt Rs. 1,20,000. Not You? Call 18002586161", S, 340.0, "AMAZON"),
        Case("HDFC VPA debit", "HDFCBK", "HDFC Bank: Rs.2,500.00 debited from a/c **1234 on 24-10-26 to VPA friend@oksbi(UPI Ref No 429387461234)", S, 2500.0, "friend@oksbi"),
        Case("HDFC salary NEFT", "HDFCBK", "HDFC Bank: Rs.45,000.00 credited to a/c XX1234 on 24-10-26 by NEFT-SALARY ACME LTD. Avl bal Rs.60,000.00", R, 45000.0, null, 60000.0),
        Case("HDFC balance enquiry (reject)", "VD-HDFCBN-P", "HDFC Bank: Your a/c XX1234 avl bal is Rs 25,000.00 as of 24-Oct-26. Not you? Call 18002586161", null),
        Case("HDFC loan promo (reject)", "VD-HDFCBN-P", "HDFC Bank: Pre-approved Personal Loan of Rs 5,00,000 credited to your account in 10 minutes. Apply now: hdfcbk.io/xyz", null),
        // ---- ICICI
        Case("ICICI debit", "ICICIB", "ICICI Bank Acct XX123 debited for Rs 500.00 on 24-Oct-26; SWIGGY credited. UPI:429387461234. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.", S, 500.0, "SWIGGY"),
        Case("ICICI credit", "ICICIB", "Dear Customer, Acct XX123 is credited with Rs 2,000.00 on 24-Oct-26 from RAHUL KUMAR. UPI:429387461234-ICICI Bank.", R, 2000.0, "RAHUL KUMAR"),
        Case("ICICI credit-card txn", "ICICIB", "Dear Customer, ICICI Bank Credit Card XX1234 has been used for a transaction of INR 1,500.00 on 24-Oct-26 at AMAZON PAY. Avl Limit: INR 98,500.00. If not you, call 18002662", S, 1500.0, "AMAZON PAY"),
        Case("ICICI ATM", "ICICIB", "Rs 2,000.00 withdrawn from ICICI Bank ATM XX1234 on 24-Oct-26. Avl Bal Rs 15,000.00", S, 2000.0, null, 15000.0),
        // ---- SBI
        Case("SBI UPI debit (no Rs)", "SBIUPI", "Dear UPI user A/C X1234 debited by 500.0 on date 24Oct26 trf to SWIGGY Refno 429387461234 If not u? call 1800111109 for other services-18001234-SBI", S, 500.0, "SWIGGY"),
        Case("SBI credit", "SBIINB", "Dear SBI User, your A/c X1234-credited by Rs.2000 on 24Oct26 transfer from RAHUL Ref No 429387461234 -SBI", R, 2000.0, "RAHUL"),
        Case("SBI IMPS debit", "SBIINB", "Your a/c no. XXXXXXXX1234 is debited for Rs.1,000.00 on 24-10-26 and a/c XXXXXXXX9876 credited (IMPS Ref no 429387461234).", S, 1000.0),
        Case("SBI debit then credited-to", "SBIUPI", "Rs.500 debited A/cX1234 24-10-26 11:45:12 and credited to a/c 987654321 (UPI Ref no 429387461234)-SBI", S, 500.0),
        // ---- Axis
        Case("Axis UPI debit", "AXISBK", "Rs. 500.00 debited from A/c no. XX1234 on 24-10-26 11:45:12 IST. UPI/P2M/429387461234/SWIGGY. Not you? SMS BLOCK UPI to 18001035577", S, 500.0, "SWIGGY"),
        Case("Axis UPI credit", "AXISBK", "INR 2,000.00 credited to A/c no. XX1234 on 24-10-26 11:45:12 IST. UPI/P2A/429387461234/Rahul. Axis Bank", R, 2000.0, "Rahul"),
        Case("Axis card spend", "AXISBK", "Spent Card no. XX1234 INR 500.00 24-10-26 11:45:12 AMAZON Avl Lmt INR 90,000 SMS BLOCK 1234 to 5676782", S, 500.0),
        // ---- Kotak
        Case("Kotak sent", "KOTAKB", "Sent Rs.500.00 from Kotak Bank AC X1234 to swiggy@icici on 24-10-26.UPI Ref 429387461234. Not you? Call 18602662666", S, 500.0, "swiggy@icici"),
        Case("Kotak received", "KOTAKB", "Received Rs.2,000.00 in your Kotak Bank AC X1234 from rahul@ybl on 24-10-26.UPI Ref:429387461234.", R, 2000.0, "rahul@ybl"),
        // ---- Central Bank of India (sender code CENTBK)
        Case("CBI debit", "JK-CENTBK-T", "Dear Customer, Your A/c no. XXXXXX1234 has been debited by Rs.500.00 on 24-10-2026 14:05:11 UPI/429387461234/Swiggy. Avl Bal Rs.1,200.00. -Central Bank of India", S, 500.0, "Swiggy", 1200.0),
        Case("CBI credit", "JX-CENTBK-T", "Dear Customer, Your A/c no. XXXXXX1234 has been credited by Rs.2,000.00 on 24-10-2026 14:05:11 UPI/429387461234/Rahul. Avl Bal Rs.3,200.00. -Central Bank of India", R, 2000.0, "Rahul", 3200.0),
        // ---- Bank of Baroda (Dr. / Cr.)
        Case("BoB Dr/Cr debit", "BOBSMS", "Rs.500.00 Dr. from A/C XXXXXX1234 and Cr. to swiggy@icici. Ref:429387461234. AvlBal:Rs1200.00(2026:10:24 11:45:12) -Bank of Baroda", S, 500.0, "swiggy@icici", 1200.0),
        Case("BoB Cr credit", "BOBSMS", "Rs.2000.00 Cr. to A/C XXXXXX1234 by rahul@ybl. Ref:429387461234. AvlBal:Rs3200.00(2026:10:24 11:45:12) -Bank of Baroda", R, 2000.0, "rahul@ybl", 3200.0),
        // ---- PNB / Canara / Union / Indian / IDFC / Yes
        Case("PNB debit", "PNBSMS", "A/c XX1234 debited INR 500.00 on 24-10-2026 11:45:12 Info: UPI/429387461234/SWIGGY. Avl Bal: INR 1,000.00. -PNB", S, 500.0, "SWIGGY", 1000.0),
        Case("PNB credit", "PNBSMS", "A/c XX1234 credited INR 2,000.00 on 24-10-2026 Info: UPI/429387461234/RAHUL. Avl Bal INR 3,000.00 -PNB", R, 2000.0, "RAHUL", 3000.0),
        Case("Canara debit", "CANBNK", "An amount of INR 500.00 has been DEBITED to your account XXX1234 on 24/10/2026. Total Avail.bal INR 1,000.00. - Canara Bank", S, 500.0, null, 1000.0),
        Case("Canara credit", "CANBNK", "An amount of INR 2,000.00 has been CREDITED to your account XXX1234 on 24/10/2026. Total Avail.bal INR 3,000.00. - Canara Bank", R, 2000.0, null, 3000.0),
        Case("Union debit", "UNIONB", "A/c *1234 Debited for Rs:500.00 on 24-10-2026 11:45:12 by UPI Ref No 429387461234 Avl Bal Rs:1200.00 -Union Bank of India", S, 500.0, null, 1200.0),
        Case("Union credit + OTP disclaimer", "UNIONB", "A/c *1234 Credited for Rs:2000.00 on 24-10-2026 by Mobile Banking Avl Bal Rs:2527.63.Never Share OTP/PIN/CVV-Union Bank of India", R, 2000.0, null, 2527.63),
        Case("Indian Bank debit", "INDBNK", "A/c *1234 debited Rs. 500.00 on 24-10-26 & credited to swiggy@icici. UPI Ref 429387461234. Bal Rs. 1,200.00 -Indian Bank", S, 500.0, "swiggy@icici", 1200.0),
        Case("IDFC 'debit of'", "IDFCFB", "Your A/C XXXX1234 has a debit of INR 500.00 on 24-OCT-2026 via UPI to SWIGGY. Balance INR 1,200.00. -IDFC FIRST Bank", S, 500.0, "SWIGGY", 1200.0),
        Case("Yes Bank debit", "YESBNK", "Rs.500.00 debited from A/c XXXX1234 UPI Ref 429387461234 to swiggy@icici. Bal Rs.1,200.00 -YES BANK", S, 500.0, "swiggy@icici", 1200.0),
        // ---- Slice (works today; regression)
        Case("Slice paid", "JD-SLCBNK-S", "Rs.39.00 paid to The pandit ji paan shop via UPI from slice A/c XX1234. Avl Bal Rs 1,000.00", S, 39.0, "The pandit ji paan shop", 1000.0),
        // ---- Autopay / mandate
        Case("AutoPay real debit", "HDFCBK", "Rs.649.00 debited from A/c XX1234 on 24-10-26 towards NETFLIX using UPI Mandate. UPI Ref 429387461234. Avl bal Rs 5,000.00", S, 649.0),
        Case("AutoPay pre-debit notice (reject)", "HDFCBK", "Pre-debit notification: Rs 649.00 will be debited from your A/c XX1234 on 26-Oct-26 towards NETFLIX UPI Mandate.", null),
        Case("Mandate created (reject)", "HDFCBK", "You have successfully created a UPI Mandate of Rs 649.00 for NETFLIX on your HDFC Bank A/c XX1234. UMN abc@hdfcbank", null),
        // ---- must be rejected
        Case("OTP (reject)", "HDFCBK", "Your OTP for transaction of Rs 500 at AMAZON is 445566. Valid for 5 min. Never share OTP -HDFC Bank", null),
        Case("Recharge (reject)", "AIRTEL", "Recharge of Rs 299 successful for 9876543210. Credited to your Airtel number", null),
        Case("Loan promo (reject)", "SalarySe", "SalarySe: Get instant loan up to Rs 5,00,000 credited to your bank account in 5 mins. Apply now: sse.in/x", null),
        Case("Balance only (reject)", "SBIINB", "Your A/c XX1234 balance is Rs 1,000.00 as on 24-Oct-26 -SBI", null),
        // ---- more things that must NOT become transactions
        Case("EMI reminder (reject)", "HDFCBK", "EMI of Rs 5,000.00 will be debited from your A/c XX1234 on 05-Nov-26. Please maintain sufficient balance. -HDFC Bank", null),
        Case("Card blocked (reject)", "ICICIB", "Dear Customer, your ICICI Bank Debit Card XX1234 has been blocked as requested. Call 18002662 for help.", null),
        Case("Minimum balance (reject)", "SBIINB", "Dear Customer, your A/c XX1234 requires a minimum balance of Rs 1000 to avoid charges -SBI", null),
        Case("Credit card limit notice (reject)", "AXISBK", "Your Axis Bank Credit Card XX1234 available limit is INR 90,000. Total due INR 1,200 by 05-Nov-26", null),
        // ---- formats confirmed on real devices earlier (regression guard)
        Case("SBI-style 'debited by' without Rs", "SBIUPI", "Dear UPI user A/C X8100 debited by 75.00 on date 20Sep26 trf to TEA STALL Refno 123456789012 If not u? call 1800111109", S, 75.0, "TEA STALL"),
        // ---- app notifications
        Case("GPay paid", "com.google.android.apps.nbu.paisa.user", "Paid ₹50 to Ramesh via UPI from your bank account", S, 50.0)
    )
}


/** What is wrong with the parser's answer for [c]; empty when it is right. */
object CorpusCheck {
    fun problems(parser: TransactionParser, c: Case): List<String> {
        val t = parser.parse(c.sender, c.text, 1_761_300_000_000L)
        val problems = ArrayList<String>()
        if (c.dir == null) {
            if (t != null) problems.add("should be rejected but got ${t.direction} ${t.amount} review=${t.needsReview}")
        } else if (t == null) {
            problems.add("rejected (expected ${c.dir} ${c.amount})")
        } else {
            if (t.direction != c.dir) problems.add("direction ${t.direction} != ${c.dir}")
            if (c.amount != null && t.amount != c.amount) problems.add("amount ${t.amount} != ${c.amount}")
            val m = t.merchantOrContact
            if (c.merchant != null && (m == null || !m.lowercase().contains(c.merchant.lowercase()))) problems.add("merchant '$m' lacks '${c.merchant}'")
            if (c.balance != null && t.balanceAfter != c.balance) problems.add("balance ${t.balanceAfter} != ${c.balance}")
        }
        return problems
    }
}

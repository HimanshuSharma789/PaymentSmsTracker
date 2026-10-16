package com.example.paymentsmstracker.config

import com.example.paymentsmstracker.theme.ThemeMode

/**
 * Named capture groups supported in [SmsParseRule.pattern]:
 * amount, merchant, date, reference, accountHint
 */
data class SmsParseRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val pattern: String,
    val dateFormat: String = "",
    val defaultAccount: String = "",
    val isExpense: Boolean = true,
    /** When true, treat as Transfer-Out and use merchant as destination account hint */
    val isTransfer: Boolean = false
)

data class AppConfig(
    val accounts: List<String> = DEFAULT_ACCOUNTS,
    val expenseCategories: Map<String, List<String>> = DEFAULT_EXPENSE_CATEGORIES,
    val incomeCategories: List<String> = DEFAULT_INCOME_CATEGORIES,
    val debtCategory: String = "Social Life",
    val debtSubcategory: String = "Dues",
    /**
     * When true, split expenses export the full amount to your MM category
     * (no dues row). Split table / Splitwise still use equal shares.
     */
    val mmFullSplitAsOwnExpense: Boolean = false,
    val smsParseRules: List<SmsParseRule> = DEFAULT_SMS_RULES,
    /** Ignore a new SMS if same ref (or amount+merchant+day) within this many minutes */
    val dedupeWindowMinutes: Int = 30,
    val autoSaveFromSms: Boolean = true,
    /** Nightly reminder to review pending transactions */
    val nightlyReviewReminderEnabled: Boolean = true,
    /** Hour of day in 24h format, e.g. 21 = 9 PM */
    val nightlyReviewHour: Int = 21,
    val nightlyReviewMinute: Int = 0,
    /**
     * Appearance: light | system | dynamic
     * dynamic = follow system day/night + Material You colors when available.
     */
    val themeMode: String = ThemeMode.DEFAULT,
    /**
     * SMS senders containing any of these (case-insensitive) are read as bank SMS, live and in
     * backfill. Empty = accept every sender. Indian bank IDs look like "VM-HDFCBK", "AD-SBIUPI".
     */
    val bankSenderTokens: List<String> = DEFAULT_BANK_SENDER_TOKENS
) {
    /** Null/blank sender (Admin test SMS) is always accepted. */
    fun isBankSender(address: String?): Boolean {
        if (address.isNullOrBlank() || bankSenderTokens.isEmpty()) return true
        val normalized = address.uppercase()
        return bankSenderTokens.any { normalized.contains(it.uppercase()) }
    }

    companion object {
        val DEFAULT_BANK_SENDER_TOKENS = listOf(
            "SBI", "HDFC", "HSBC", "ICICI", "AXIS", "KOTAK", "IDFC", "YESBNK", "YESBANK", "INDUS",
            "PNB", "CANBK", "BOI", "UNION", "BOB", "BARODA", "FEDBNK", "FEDERAL", "IDBI", "CENTBK",
            "IOB", "UCO", "RBL", "AUBANK", "BANDHN", "KVB", "KBL", "SIBSMS", "CITI", "SCBANK", "DBS",
            "AMEX", "PAYTM", "AIRTEL", "AIRBNK", "PHONEPE", "GPAY", "GOOGLEPAY", "AMAZONPAY",
            "MOBIKWIK", "FREECHARGE", "CRED", "SLICE", "ONECARD", "JUPITER", "NIYO", "BHIM"
        )

        val DEFAULT_ACCOUNTS = listOf(
            "Cash",
            "SBI",
            "Paytm",
            "Airtel",
            "Amazon",
            "HSBC",
            "Card",
            "SBI cashback credit card",
            "HDFC credit card",
            "ICICI credit card",
            "HSBC debit card",
            "Mutual Funds",
            "Gold"
        )

        val DEFAULT_INCOME_CATEGORIES = listOf(
            "Allowance", "Internship", "Salary", "Reimbursement",
            "Petty cash", "Bonus", "Other", "Loan"
        )

        val DEFAULT_EXPENSE_CATEGORIES = mapOf(
            "Food" to listOf("Lunch", "Dinner", "Eating out", "Beverages", "Juice"),
            "Social Life" to listOf("Friend", "Fellowship", "Alumni", "Dues", "Festival"),
            "Transport" to listOf("Bus", "Taxi", "Car", "Bike", "Flight", "Other", "Fuel"),
            "Culture" to listOf("Books", "Movie", "Music", "Apps", "Drawing", "Exhibitions"),
            "Household" to listOf("Appliances", "Furniture", "Kitchen", "Toiletries", "Chandlery"),
            "Apparel" to listOf("Clothing", "Fashion", "Shoes", "Laundry"),
            "Beauty" to listOf("Cosmetics", "Makeup", "Accessories", "Haircut"),
            "Health" to listOf("Health", "Yoga", "Hospital", "Medicine", "Gym", "Sports"),
            "Education" to listOf("Online courses", "Books", "Subscription", "Event"),
            "Trip" to listOf("Food", "Travel", "Stay", "Other"),
            "Rent" to listOf("Water", "Electricity", "Wifi", "Room", "Household"),
            "Self" to listOf("Gadgets", "Telecom", "Other"),
            "Family" to listOf("Function", "Food", "Travel", "Other", "Health", "Clothing"),
            "Gift" to emptyList(),
            "Other" to emptyList(),
            "Subscription" to emptyList(),
            "Investment" to emptyList(),
            "Credit bill payment" to emptyList()
        )

        /** v1 generic pattern: its trailing lazy `.*?` made the reference group always empty. */
        const val GENERIC_PAID_TO_ON_PATTERN_V1 =
            """(?i)(?:INR|Rs\.?|₹)\s*(?<amount>[\d,]+(?:\.\d{1,2})?).*?\bto\s+(?<merchant>.+?)\s+on\s+(?<date>\d{1,2}[-/]\w{3}[-/]\d{2,4}|\d{1,2}\s\w{3}\s\d{2,4}).*?(?:ref(?:erence)?\s*(?:no\.?)?\s*(?<reference>[\w.-]+))?"""

        const val GENERIC_PAID_TO_ON_PATTERN =
            """(?i)(?:INR|Rs\.?|₹)\s*(?<amount>[\d,]+(?:\.\d{1,2})?).*?\bto\s+(?<merchant>.+?)\s+on\s+(?<date>\d{1,2}[-/]\w{3}[-/]\d{2,4}|\d{1,2}\s\w{3}\s\d{2,4})(?:.*?\bref(?:erence)?\.?\s*(?:no\.?)?\s*[:#]?\s*(?<reference>\d[\w-]{5,}))?"""

        val DEFAULT_SMS_RULES = listOf(
            SmsParseRule(
                id = "hdfc_card_spend",
                name = "HDFC Card Spend",
                pattern = """(?i)Spent\s+Rs\.?\s*(?<amount>[\d,]+(?:\.\d{1,2})?)\s+On\s+HDFC.*?At\s+(?<merchant>.+?)\s+On\s+(?<date>\d{4}-\d{2}-\d{2}(?::\d{2}:\d{2}:\d{2})?)""",
                dateFormat = "yyyy-MM-dd:HH:mm:ss",
                defaultAccount = "HDFC credit card"
            ),
            SmsParseRule(
                id = "icici_card_spend",
                name = "ICICI Card Spend",
                pattern = """(?i)INR\s+(?<amount>[\d,]+(?:\.\d{1,2})?)\s+spent\s+using\s+ICICI\s+Bank\s+Card\s+\S+\s+on\s+(?<date>\d{1,2}-\w{3}-\d{2,4})\s+on\s+(?<merchant>.+?)(?:\.\s*Avl|\.|$)""",
                dateFormat = "dd-MMM-yy",
                defaultAccount = "ICICI credit card"
            ),
            SmsParseRule(
                id = "sbi_upi_debit",
                name = "SBI UPI Debit",
                pattern = """(?i)A/C\s+X?\w+\s+debited\s+by\s+(?<amount>[\d,]+(?:\.\d+)?)\s+on\s+date\s+(?<date>\d{1,2}\w{3}\d{2,4})\s+trf\s+to\s+(?<merchant>.+?)\s+Refno\s+(?<reference>[\w.-]+)""",
                dateFormat = "ddMMMyy",
                defaultAccount = "SBI"
            ),
            SmsParseRule(
                id = "hsbc_debited_towards",
                name = "HSBC Debited Towards",
                pattern = """(?i)HSBC\s+Acc\s+\w+\s+is\s+debited\s+for\s+INR\s+(?<amount>[\d,]+(?:\.\d{1,2})?)\s+on\s+(?<date>\d{1,2}-\w{3}-\d{2,4})\s+towards\s+(?<merchant>.+?)\.\s*(?:UPI\s+Ref\s+No\s+(?<reference>\d+))?""",
                dateFormat = "dd-MMM-yy",
                defaultAccount = "HSBC"
            ),
            SmsParseRule(
                id = "hsbc_paid_to",
                name = "HSBC Paid To",
                pattern = """(?i)INR\s+(?<amount>[\d,]+(?:\.\d{1,2})?)\s+is\s+paid\s+from\s+HSBC\s+account\s+\w+\s+to\s+(?<merchant>.+?)\s+on\s+(?<date>\d{1,2}-\w{3}-\d{2,4})\s+with\s+ref\s+(?<reference>\d+)""",
                dateFormat = "dd-MMM-yy",
                defaultAccount = "HSBC"
            ),
            SmsParseRule(
                id = "hsbc_debit_card",
                name = "HSBC Debit Card",
                pattern = """(?i)HSBC\s+Debit\s+Card\s+\w+\s+for\s+INR\s+(?<amount>[\d,]+(?:\.\d{1,2})?)\s+on\s+(?<date>\d{1,2}[A-Za-z]{3}(?:\d{2,4})?)\s+at\s+(?<merchant>.+?)(?:\s*\.|Your\s+available|$)""",
                dateFormat = "ddMMM",
                defaultAccount = "HSBC debit card"
            ),
            SmsParseRule(
                id = "sbi_yono_transfer",
                name = "SBI YONO Transfer",
                pattern = """(?i)Ac\s+x?\w+\s+debited\s+Rs\.?\s*(?<amount>[\d,]+(?:\.\d{1,2})?)\s+for\s+transfer\s+to\s+(?<merchant>.+?)\s+dt\s+(?<date>\d{1,2}\.\d{1,2}\.\d{2,4})\s+Ref\s+(?<reference>\d+)""",
                dateFormat = "dd.MM.yy",
                defaultAccount = "SBI"
            ),
            SmsParseRule(
                id = "airtel_apb_debit",
                name = "Airtel Payments Bank Debit",
                pattern = """(?i)Rs\.?\s*(?<amount>[\d,]+(?:\.\d{1,2})?)\s+debited\s+from\s+(?<merchant>Airtel Payments Bank).*?Txn\s*ID\s*(?<reference>\d+)""",
                dateFormat = "",
                defaultAccount = "Airtel"
            ),
            SmsParseRule(
                id = "generic_paid_to_on",
                name = "Generic Paid To On",
                pattern = GENERIC_PAID_TO_ON_PATTERN,
                dateFormat = "dd-MMM-yy",
                defaultAccount = ""
            )
        )
    }
}

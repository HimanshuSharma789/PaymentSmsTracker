package com.example.paymentsmstracker.sms

import android.location.Location
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.merchant.MerchantMemory
import java.util.concurrent.TimeUnit

/**
 * The one path from an SMS to a review row: sender filter → parse → dedupe → merchant memory
 * → insert. Live receiver, inbox backfill and Admin's test inject all go through here, so they
 * accept, skip and fill rows the same way.
 */
object SmsIngest {

    sealed interface Outcome {
        data object NotBankSender : Outcome
        data object NotPayment : Outcome
        data class Duplicate(val parsed: ParsedSms) : Outcome
        /** [id] is 0 when [insert] was false (auto-save off). */
        data class Accepted(val parsed: ParsedSms, val transaction: Transaction, val id: Long) : Outcome
    }

    /**
     * @param existing rows to dedupe against; backfill passes its running list. When null the
     * relevant rows are loaded from the DB (same day ± 1 day, same reference, same body).
     */
    suspend fun ingest(
        db: AppDatabase,
        config: AppConfig,
        body: String,
        receivedAtMs: Long?,
        sender: String?,
        insert: Boolean = true,
        requirePaymentHeuristic: Boolean = true,
        existing: List<Transaction>? = null,
        location: suspend () -> Location? = { null }
    ): Outcome {
        if (!config.isBankSender(sender)) return Outcome.NotBankSender
        val parsed = SmsParser(config).parse(body, receivedAtMs, requirePaymentHeuristic)
            ?: return Outcome.NotPayment

        val dao = db.transactionDao()
        val candidates = existing ?: run {
            val day = TimeUnit.DAYS.toMillis(1)
            val ref = SmsDedupe.usableReference(parsed)
            (dao.getTransactionsBetween(parsed.timestamp - day, parsed.timestamp + day) +
                (if (ref.isNotEmpty()) dao.findAllByReference(ref) else emptyList()) +
                dao.findAllByOriginalSms(body)).distinctBy { it.id }
        }
        if (SmsDedupe.isDuplicateAgainst(candidates, body, parsed, config.dedupeWindowMinutes)) {
            return Outcome.Duplicate(parsed)
        }

        val filled = MerchantMemory.applyToBlankFields(
            category = "",
            subcategory = "",
            account = parsed.account,
            transactionType = parsed.transactionType,
            hint = MerchantMemory.lookup(db, parsed.merchantName)
        )
        val fix = location()
        val transaction = Transaction(
            amount = parsed.amount,
            merchantName = parsed.merchantName,
            category = filled.category,
            subcategory = filled.subcategory,
            account = filled.account,
            timestamp = parsed.timestamp,
            originalSms = body,
            isExpense = parsed.isExpense,
            referenceNumber = parsed.referenceNumber,
            reviewStatus = ReviewStatus.NEEDS_REVIEW,
            transactionType = parsed.transactionType,
            latitude = fix?.latitude,
            longitude = fix?.longitude
        )
        val id = if (insert) dao.insertTransaction(transaction) else 0L
        return Outcome.Accepted(parsed, transaction.copy(id = id), id)
    }
}

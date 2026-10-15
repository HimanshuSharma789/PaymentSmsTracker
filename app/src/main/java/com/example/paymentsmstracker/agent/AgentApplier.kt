package com.example.paymentsmstracker.agent

import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode

/**
 * The form fields of [this] tx, participants and allocations re-keyed onto the current
 * group chip [labels] (label to Splitwise user id), as the entry screen shows them.
 */
fun Transaction.toFormState(labels: List<Pair<String, Long>>): FormState {
    val type = MoneyManagerExporter.normalizeType(transactionType)
    val saved = participantList()
    val idMap = participantIdMap()
    val people = if (type == "Transfer-Out") emptyList() else saved.map { name ->
        val id = idMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        labels.firstOrNull { id != null && it.second == id }?.first
            ?: labels.firstOrNull { it.first.equals(name, ignoreCase = true) }?.first
            ?: name
    }
    val mode = if (people.isEmpty()) SplitMode.EQUAL else SplitMode.normalize(splitMode)
    val parsed = SplitCalculator.parseAllocations(splitAllocations)
    val current = people.map { label -> label to (labels.firstOrNull { it.first == label }?.second ?: 0L) }
    val allocations = if (mode == SplitMode.EQUAL) emptyMap()
    else SplitCalculator.remapAllocationsToLabels(parsed, idMap, saved, current).ifEmpty { parsed }
    return FormState(
        merchantName = merchantName,
        transactionType = type,
        account = account,
        category = category,
        subcategory = subcategory,
        notes = notes.orEmpty(),
        participants = people,
        splitMode = mode,
        splitAllocations = allocations
    )
}

/**
 * Mirrors TransactionEntryActivity.saveOrUpdateTransaction's Transaction construction.
 * Status: [markReviewed] → reviewed; otherwise unchanged. Exported always stays exported.
 */
fun FormState.applyTo(
    tx: Transaction,
    groupId: Long,
    labelToId: Map<String, Long>,
    markReviewed: Boolean
): Transaction {
    val type = MoneyManagerExporter.normalizeType(transactionType)
    val transfer = type == "Transfer-Out"
    val people = if (transfer) emptyList() else participants
    val mode = if (people.isEmpty()) SplitMode.EQUAL else SplitMode.normalize(splitMode)
    val allocations = if (mode == SplitMode.EQUAL) emptyMap() else splitAllocations
    val idMap = linkedMapOf<String, Long>()
    people.forEach { p -> labelToId[p]?.takeIf { it > 0L }?.let { idMap[p] = it } }
    val status = when {
        tx.reviewStatus == ReviewStatus.EXPORTED -> ReviewStatus.EXPORTED
        markReviewed -> ReviewStatus.REVIEWED
        else -> tx.reviewStatus
    }
    return tx.copy(
        merchantName = merchantName.trim().ifEmpty { if (transfer) "Transfer to $category" else tx.merchantName },
        category = category,
        subcategory = if (transfer) "" else subcategory,
        account = account,
        notes = notes.trim().ifEmpty { null },
        isExpense = type != "Income",
        participants = people.joinToString(","),
        splitMode = mode,
        splitAllocations = if (transfer) "" else SplitCalculator.encodeAllocations(allocations, idMap),
        reviewStatus = status,
        transactionType = type,
        splitwiseGroupId = if (people.isEmpty()) 0L else groupId,
        splitwiseParticipantIds = Transaction.encodeParticipantIds(idMap),
        lastUpdatedTimestamp = System.currentTimeMillis()
    )
}

object AgentApplier {

    /** Writes [proposal]; returns (old, new) for Undo. Refuses when the tx changed after the proposal was built. */
    suspend fun apply(db: AppDatabase, proposal: Proposal): Result<Pair<Transaction, Transaction>> = runCatching {
        val dao = db.transactionDao()
        val fresh = dao.getTransactionById(proposal.tx.id) ?: error("Transaction was deleted")
        if (fresh.lastUpdatedTimestamp != proposal.tx.lastUpdatedTimestamp) error("Changed since proposal, ask again")
        val updated = proposal.suggestion.after.applyTo(fresh, proposal.groupId, proposal.labelToId, proposal.markReviewed)
        dao.updateTransaction(updated)
        if (updated.category.isNotBlank()) {
            MerchantMemory.remember(
                db = db,
                merchant = updated.merchantName,
                category = updated.category,
                subcategory = updated.subcategory,
                account = updated.account,
                transactionType = updated.transactionType
            )
        }
        fresh to updated
    }

    /** Restores [old] unless the tx was edited again after [applied]. Merchant memory is not rolled back. */
    suspend fun undo(db: AppDatabase, old: Transaction, applied: Transaction): Result<Unit> = runCatching {
        val dao = db.transactionDao()
        val fresh = dao.getTransactionById(old.id) ?: error("Transaction was deleted")
        if (fresh.lastUpdatedTimestamp != applied.lastUpdatedTimestamp) error("Edited since apply, undo it by hand")
        dao.updateTransaction(old)
    }
}

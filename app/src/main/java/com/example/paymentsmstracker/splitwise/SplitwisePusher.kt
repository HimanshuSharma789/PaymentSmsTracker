package com.example.paymentsmstracker.splitwise

import com.example.paymentsmstracker.data.SplitwiseExpenseIds
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.split.SplitCalculator
import java.math.BigDecimal
import java.math.RoundingMode

data class SplitwisePushResult(
    val transactionId: Long,
    val expenseId: Long? = null,
    val error: String? = null,
    val unauthorized: Boolean = false
) {
    val isSuccess: Boolean get() = error == null && expenseId != null
}

object SplitwisePusher {

    fun pushOne(
        api: SplitwiseApi,
        auth: SplitwiseAuthState,
        tx: Transaction,
        currencyCode: String = "INR"
    ): SplitwisePushResult {
        if (!SplitwiseExporter.isSplit(tx)) {
            return SplitwisePushResult(tx.id, error = "Not a split expense")
        }
        if (!com.example.paymentsmstracker.data.ReviewStatus.canPushToSplitwise(tx.reviewStatus)) {
            return SplitwisePushResult(tx.id, error = "Mark as reviewed before pushing to Splitwise")
        }
        if (SplitwiseExpenseIds.isDone(tx.splitwiseExpenseId)) {
            return SplitwisePushResult(
                tx.id,
                expenseId = tx.splitwiseExpenseId.takeIf { SplitwiseExpenseIds.isApiPush(it) },
                error = if (SplitwiseExpenseIds.isManualSuccess(tx.splitwiseExpenseId)) {
                    "Already marked as success"
                } else {
                    "Already pushed (expense #${tx.splitwiseExpenseId})"
                }
            )
        }
        val groupId = tx.splitwiseGroupId
        if (groupId <= 0L) {
            return SplitwisePushResult(tx.id, error = "Choose a Splitwise group first")
        }
        val group = auth.groupById(groupId)
            ?: return SplitwisePushResult(tx.id, error = "Group not synced — refresh groups in Admin")

        val people = tx.participantList().ifEmpty { listOf("ME") }
        val split = SplitCalculator.resolve(tx)
        if (!split.isValid) {
            return SplitwisePushResult(tx.id, error = split.error ?: "Invalid split")
        }
        val amounts = split.amounts
        if (amounts.isEmpty()) {
            return SplitwisePushResult(tx.id, error = "No share amounts")
        }

        val paidBy = auth.currentUserId
        if (paidBy <= 0L) {
            return SplitwisePushResult(tx.id, error = "Signed-in Splitwise user unknown — sign in again")
        }

        val owedShares = linkedMapOf<Long, String>()
        val idMap = tx.participantIdMap()
        for (name in people) {
            val userId = idMap.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?: return SplitwisePushResult(
                    tx.id,
                    error = "Missing Splitwise user id for \"$name\" — re-select group members on the split"
                )
            if (group.members.none { it.id == userId } && userId != paidBy) {
                return SplitwisePushResult(
                    tx.id,
                    error = "\"$name\" is not in group \"${group.name}\""
                )
            }
            val owed = amounts.entries
                .firstOrNull { it.key.equals(name, true) }
                ?.value
                ?: BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            owedShares[userId] = owed.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }

        // Ensure payer is in the payload even if somehow omitted
        if (!owedShares.containsKey(paidBy)) {
            owedShares[paidBy] = "0.00"
        }

        val cost = BigDecimal.valueOf(tx.amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val details = buildString {
            val cat = listOf(tx.category, tx.subcategory).filter { it.isNotBlank() }.joinToString(" / ")
            if (cat.isNotBlank()) append(cat)
            if (!tx.notes.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(tx.notes)
            }
            if (tx.referenceNumber.orEmpty().isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("Ref ${tx.referenceNumber}")
            }
        }.ifBlank { null }

        return try {
            val expenseId = api.createExpense(
                groupId = groupId,
                description = tx.merchantName.ifBlank { "Expense" },
                cost = cost,
                currencyCode = currencyCode,
                dateMillis = tx.timestamp,
                paidByUserId = paidBy,
                owedShares = owedShares,
                details = details
            )
            SplitwisePushResult(tx.id, expenseId = expenseId)
        } catch (e: SplitwiseUnauthorizedException) {
            SplitwisePushResult(
                tx.id,
                error = e.message ?: "Session expired",
                unauthorized = true
            )
        } catch (e: Exception) {
            SplitwisePushResult(
                tx.id,
                error = e.message ?: "Push failed",
                unauthorized = SplitwiseSessionEvents.isUnauthorized(e)
            )
        }
    }
}

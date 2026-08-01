package com.example.paymentsmstracker.split

import com.example.paymentsmstracker.data.Transaction
import java.math.BigDecimal
import java.math.RoundingMode

object SplitMode {
    const val EQUAL = "equal"
    const val EXACT = "exact"
    const val PERCENT = "percent"
    const val WEIGHT = "weight"

    val ALL = listOf(EQUAL, EXACT, PERCENT, WEIGHT)

    fun label(mode: String): String = when (mode) {
        EXACT -> "Uneven"
        PERCENT -> "Percentage"
        WEIGHT -> "Shares"
        else -> "Equal"
    }

    fun normalize(mode: String?): String =
        if (mode.isNullOrBlank()) EQUAL else mode.lowercase()
}

data class SplitAllocationResult(
    val amounts: Map<String, BigDecimal>,
    val myShare: BigDecimal,
    val friendsOwe: BigDecimal,
    val error: String? = null
) {
    val isValid: Boolean get() = error == null
}

object SplitCalculator {

    fun parseAllocations(raw: String?): Map<String, BigDecimal> {
        if (raw.isNullOrBlank()) return emptyMap()
        val map = linkedMapOf<String, BigDecimal>()
        raw.split(',', '|').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val sep = when {
                trimmed.contains('=') -> '='
                trimmed.contains(':') -> ':'
                else -> return@forEach
            }
            val idx = trimmed.indexOf(sep)
            if (idx <= 0 || idx == trimmed.lastIndex) return@forEach
            val name = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim().toBigDecimalOrNull() ?: return@forEach
            if (name.isNotEmpty()) map[name] = value
        }
        return map
    }

    fun encodeAllocations(values: Map<String, BigDecimal>): String =
        values.entries.joinToString(",") { "${it.key}:${it.value.stripTrailingZeros().toPlainString()}" }

    fun resolve(
        totalAmount: Double,
        participants: List<String>,
        mode: String,
        allocationInputs: Map<String, BigDecimal>
    ): SplitAllocationResult {
        val total = BigDecimal.valueOf(totalAmount).setScale(2, RoundingMode.HALF_UP)
        val people = participants.map { it.trim() }.filter { it.isNotEmpty() }

        if (people.isEmpty()) {
            return SplitAllocationResult(
                amounts = emptyMap(),
                myShare = total,
                friendsOwe = BigDecimal.ZERO.setScale(2)
            )
        }

        val normalizedMode = SplitMode.normalize(mode)
        val amounts = when (normalizedMode) {
            SplitMode.EXACT -> resolveExact(total, people, allocationInputs)
            SplitMode.PERCENT -> resolvePercent(total, people, allocationInputs)
            SplitMode.WEIGHT -> resolveWeight(total, people, allocationInputs)
            else -> resolveEqual(total, people)
        }

        if (amounts.error != null) {
            return SplitAllocationResult(
                amounts = emptyMap(),
                myShare = BigDecimal.ZERO.setScale(2),
                friendsOwe = BigDecimal.ZERO.setScale(2),
                error = amounts.error
            )
        }

        val map = amounts.amounts
        val myShare = map.entries
            .firstOrNull { it.key.equals("ME", ignoreCase = true) }
            ?.value
            ?: BigDecimal.ZERO.setScale(2)
        val friendsOwe = total.subtract(myShare).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        return SplitAllocationResult(
            amounts = map,
            myShare = myShare,
            friendsOwe = friendsOwe
        )
    }

    fun resolve(transaction: Transaction): SplitAllocationResult =
        resolve(
            totalAmount = transaction.amount,
            participants = transaction.participantList(),
            mode = transaction.splitMode,
            allocationInputs = parseAllocations(transaction.splitAllocations)
        )

    private data class Intermediate(
        val amounts: Map<String, BigDecimal> = emptyMap(),
        val error: String? = null
    )

    private fun resolveEqual(total: BigDecimal, people: List<String>): Intermediate {
        val share = total.divide(BigDecimal(people.size), 2, RoundingMode.HALF_UP)
        val map = linkedMapOf<String, BigDecimal>()
        people.forEach { map[it] = share }
        return Intermediate(adjustRounding(map, people, total))
    }

    private fun resolveExact(
        total: BigDecimal,
        people: List<String>,
        inputs: Map<String, BigDecimal>
    ): Intermediate {
        val map = linkedMapOf<String, BigDecimal>()
        for (name in people) {
            val value = lookup(inputs, name)
                ?: return Intermediate(error = "Enter amount for $name")
            if (value < BigDecimal.ZERO) {
                return Intermediate(error = "Amount for $name cannot be negative")
            }
            map[name] = value.setScale(2, RoundingMode.HALF_UP)
        }
        val sum = map.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        if (sum.compareTo(total) != 0) {
            return Intermediate(
                error = "Amounts must add up to ₹${total.toPlainString()} (now ₹${sum.toPlainString()})"
            )
        }
        return Intermediate(map)
    }

    private fun resolvePercent(
        total: BigDecimal,
        people: List<String>,
        inputs: Map<String, BigDecimal>
    ): Intermediate {
        val percents = linkedMapOf<String, BigDecimal>()
        for (name in people) {
            val value = lookup(inputs, name)
                ?: return Intermediate(error = "Enter percentage for $name")
            if (value < BigDecimal.ZERO) {
                return Intermediate(error = "Percentage for $name cannot be negative")
            }
            percents[name] = value
        }
        val percentSum = percents.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        if (percentSum.compareTo(BigDecimal("100")) != 0) {
            return Intermediate(
                error = "Percentages must add up to 100 (now ${percentSum.stripTrailingZeros().toPlainString()})"
            )
        }
        val raw = linkedMapOf<String, BigDecimal>()
        people.forEach { name ->
            raw[name] = total.multiply(percents.getValue(name))
                .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        }
        return Intermediate(adjustRounding(raw, people, total))
    }

    private fun resolveWeight(
        total: BigDecimal,
        people: List<String>,
        inputs: Map<String, BigDecimal>
    ): Intermediate {
        val weights = linkedMapOf<String, BigDecimal>()
        for (name in people) {
            val value = lookup(inputs, name)
                ?: return Intermediate(error = "Enter share for $name")
            if (value <= BigDecimal.ZERO) {
                return Intermediate(error = "Share for $name must be greater than 0")
            }
            weights[name] = value
        }
        val weightSum = weights.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        val raw = linkedMapOf<String, BigDecimal>()
        people.forEach { name ->
            raw[name] = total.multiply(weights.getValue(name))
                .divide(weightSum, 2, RoundingMode.HALF_UP)
        }
        return Intermediate(adjustRounding(raw, people, total))
    }

    /** Assign any leftover paisa to the first participant so shares sum exactly. */
    private fun adjustRounding(
        shares: Map<String, BigDecimal>,
        people: List<String>,
        total: BigDecimal
    ): Map<String, BigDecimal> {
        val mutable = shares.mapValues { it.value.setScale(2, RoundingMode.HALF_UP) }.toMutableMap()
        val sum = mutable.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        val diff = total.subtract(sum)
        if (diff.compareTo(BigDecimal.ZERO) != 0) {
            val target = people.firstOrNull { it.equals("ME", ignoreCase = true) }
                ?: people.first()
            mutable[target] = (mutable[target] ?: BigDecimal.ZERO).add(diff)
                .setScale(2, RoundingMode.HALF_UP)
        }
        return mutable
    }

    private fun lookup(inputs: Map<String, BigDecimal>, name: String): BigDecimal? =
        inputs.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

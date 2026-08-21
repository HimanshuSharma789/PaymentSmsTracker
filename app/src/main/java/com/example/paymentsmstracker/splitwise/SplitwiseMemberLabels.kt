package com.example.paymentsmstracker.splitwise

object SplitwiseMemberLabels {

    /**
     * Stable display labels for group members. The signed-in user is always [ME].
     * Duplicate names get a numeric suffix so allocations stay unique.
     */
    fun labelsFor(
        members: List<SplitwiseUser>,
        currentUserId: Long
    ): List<Pair<String, Long>> {
        val used = linkedSetOf<String>()
        return members.map { member ->
            var label = if (currentUserId > 0L && member.id == currentUserId) {
                "ME"
            } else {
                member.displayName.ifBlank { "User ${member.id}" }
            }
            val key = label.lowercase()
            if (!used.add(key)) {
                val withLast = listOf(member.firstName, member.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "User ${member.id}" }
                label = if (used.add(withLast.lowercase())) {
                    withLast
                } else {
                    val unique = "$withLast (${member.id})"
                    used.add(unique.lowercase())
                    unique
                }
            }
            label to member.id
        }
    }
}

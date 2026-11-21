package com.example.paymentsmstracker.split

/** Rules for when a row counts as a split. Only an open split section makes one. */
object SplitDefaults {
    /** Participants to persist: none unless the split section is open on a non-transfer row. */
    fun participantsToSave(transfer: Boolean, sectionOpen: Boolean, checked: List<String>): List<String> =
        if (transfer || !sectionOpen) emptyList() else checked

    /** Group to show when the user opens "Add split": keep the current one, else the default if still valid. */
    fun groupOnOpen(current: Long, defaultId: Long, validIds: Collection<Long>): Long =
        if (current > 0L) current else defaultId.takeIf { it > 0L && it in validIds } ?: 0L

    /** True when removing the split would throw away something the user set (so ask first). */
    fun hasMeaningfulSplit(
        groupId: Long, defaultId: Long, participantsDeviate: Boolean, customMode: Boolean, hasAmounts: Boolean
    ): Boolean = (groupId > 0L && groupId != defaultId) || participantsDeviate || customMode || hasAmounts
}

package com.example.paymentsmstracker.review

object CategorySuggestions {
    /**
     * Top [max] options by usage (counts keyed by lowercase name), filled in config order.
     * A [selected] option outside the top takes slot 1 and the last one drops out.
     * Lists of [max] + 1 or fewer are returned whole, in config order.
     */
    fun pick(
        options: List<String>,
        counts: Map<String, Int>,
        selected: String,
        max: Int = 5
    ): List<String> {
        if (options.size <= max + 1) return options
        val used = options
            .filter { (counts[it.lowercase()] ?: 0) > 0 }
            .sortedByDescending { counts[it.lowercase()] ?: 0 } // stable: ties keep config order
        val top = (used + options.filter { it !in used }).take(max).toMutableList()
        val sel = options.firstOrNull { it.equals(selected, ignoreCase = true) }
        if (sel != null && top.none { it.equals(sel, ignoreCase = true) }) {
            top.removeAt(top.lastIndex)
            top.add(0, sel)
        }
        return top
    }
}

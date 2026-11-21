package com.example.paymentsmstracker.config

/** Pure add/rename/remove/toggle on the account list, keeping `accountsNotSpending` consistent. */
object AccountEdits {
    /** Null when [name] (trimmed) is fine to use, else an error message. [except] is the name being renamed. */
    fun validate(config: AppConfig, name: String, except: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name."
        if (config.accounts.any { it.equals(n, true) && !it.equals(except, true) }) return "\"$n\" already exists."
        return null
    }

    private fun isOff(c: AppConfig, name: String) = c.accountsNotSpending.any { it.equals(name, true) }
    private fun without(list: List<String>, name: String) = list.filterNot { it.equals(name, true) }

    /** Appends; credit-card names start as "not spending". */
    fun add(c: AppConfig, name: String): AppConfig {
        val n = name.trim()
        return c.copy(
            accounts = c.accounts + n,
            accountsNotSpending = c.accountsNotSpending + AppConfig.defaultNotSpending(listOf(n))
        )
    }

    fun rename(c: AppConfig, old: String, name: String): AppConfig {
        val n = name.trim()
        return c.copy(
            accounts = c.accounts.map { if (it.equals(old, true)) n else it },
            accountsNotSpending = c.accountsNotSpending.map { if (it.equals(old, true)) n else it }
        )
    }

    fun remove(c: AppConfig, name: String) =
        c.copy(accounts = without(c.accounts, name), accountsNotSpending = without(c.accountsNotSpending, name))

    fun setCountsAsSpending(c: AppConfig, name: String, counts: Boolean): AppConfig {
        val rest = without(c.accountsNotSpending, name)
        return c.copy(accountsNotSpending = if (counts) rest else rest + name)
    }

    fun countsAsSpending(c: AppConfig, name: String) = !isOff(c, name)
}

package com.example.paymentsmstracker.agent

import android.content.Context
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Wire format is Anthropic Messages for [CLAUDE], OpenAI chat completions for the rest. */
enum class AgentProvider(val defaultBaseUrl: String, val defaultModel: String) {
    CLAUDE("https://api.anthropic.com", "claude-haiku-4-5-20251001"),
    // Cheap paid model (~$0.24 / $2.20 per M tokens), supports tools + tool_choice. Free ":free" models 429 often.
    OPENROUTER("https://openrouter.ai/api/v1", "qwen/qwen3.8-27b"),
    OPENAI_COMPATIBLE("https://api.openai.com/v1", "gpt-4.1-mini");

    /** Local servers (Ollama, LM Studio) usually need no key. */
    val keyRequired: Boolean get() = this != OPENAI_COMPATIBLE
}

/** Opt-in AI Assist settings. Encrypted and excluded from backup (see res/xml/backup_rules.xml). */
class AgentSettings(context: Context) {
    private val prefs = SplitwiseAuthStore.createSecurePrefs(context, "agent_secure")

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** One boolean per field; `sms` keeps its original key `include_sms`. */
    var privacy: AgentPrivacy
        get() {
            val d = AgentPrivacy()
            fun b(key: String, def: Boolean) = prefs.getBoolean(key, def)
            return AgentPrivacy(
                amounts = b("read_amounts", d.amounts),
                merchants = b("read_merchants", d.merchants),
                dates = b("read_dates", d.dates),
                categories = b("read_categories", d.categories),
                notes = b("read_notes", d.notes),
                friends = b("read_friends", d.friends),
                merchantMemory = b("read_merchant_memory", d.merchantMemory),
                history = b("read_history", d.history),
                sms = b("include_sms", d.sms),
                location = b("read_location", d.location)
            )
        }
        set(p) = prefs.edit()
            .putBoolean("read_amounts", p.amounts)
            .putBoolean("read_merchants", p.merchants)
            .putBoolean("read_dates", p.dates)
            .putBoolean("read_categories", p.categories)
            .putBoolean("read_notes", p.notes)
            .putBoolean("read_friends", p.friends)
            .putBoolean("read_merchant_memory", p.merchantMemory)
            .putBoolean("read_history", p.history)
            .putBoolean("include_sms", p.sms)
            .putBoolean("read_location", p.location)
            .apply()

    var provider: AgentProvider
        get() = runCatching { AgentProvider.valueOf(prefs.getString("provider", null).orEmpty()) }
            .getOrDefault(AgentProvider.CLAUDE)
        set(value) = prefs.edit().putString("provider", value.name).apply()

    // Key, model and URL are kept per provider so switching back and forth loses nothing.
    fun apiKey(p: AgentProvider): String = prefs.getString("key_${p.name}", "").orEmpty()
    fun model(p: AgentProvider): String = prefs.getString("model_${p.name}", "").orEmpty().ifBlank { p.defaultModel }
    fun baseUrl(p: AgentProvider): String = prefs.getString("url_${p.name}", "").orEmpty().ifBlank { p.defaultBaseUrl }

    fun save(p: AgentProvider, apiKey: String, model: String, baseUrl: String) {
        prefs.edit()
            .putString("key_${p.name}", apiKey.trim())
            .putString("model_${p.name}", model.trim())
            .putString("url_${p.name}", baseUrl.trim().trimEnd('/'))
            .apply()
    }

    val isReady: Boolean get() = enabled && (!provider.keyRequired || apiKey(provider).isNotBlank())

    fun client(): AgentClient = provider.let { AgentClient(it, apiKey(it), baseUrl(it), model(it)) }

    companion object {
        /**
         * Null when [baseUrl] may be used. https anywhere; plain http only to this device or the
         * local network (Ollama / LM Studio), so transaction data never crosses the internet in clear.
         */
        fun endpointProblem(baseUrl: String): String? {
            val url = baseUrl.trim().toHttpUrlOrNull() ?: return "Invalid URL"
            if (url.isHttps) return null
            val host = url.host
            val local = host == "localhost" || host.endsWith(".local") || host == "10.0.2.2" ||
                host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
            return if (local) null else "Use https, or http only for a server on this phone or your local network"
        }
    }
}

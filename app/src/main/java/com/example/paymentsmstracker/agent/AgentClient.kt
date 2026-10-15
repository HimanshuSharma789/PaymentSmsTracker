package com.example.paymentsmstracker.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ToolCall(val id: String, val name: String, val input: JSONObject)
data class ToolSpec(val name: String, val description: String, val schema: JSONObject)
sealed class ChatMessage {
    data class User(val text: String) : ChatMessage()
    data class Assistant(val text: String, val toolCalls: List<ToolCall>) : ChatMessage()
    data class ToolResult(val callId: String, val content: String) : ChatMessage()
}

/**
 * Provider-neutral tool-use chat. Blocking; call off the main thread.
 * [AgentProvider.CLAUDE] uses Anthropic Messages; OpenRouter and custom endpoints use OpenAI chat completions.
 */
class AgentClient(
    private val provider: AgentProvider,
    private val apiKey: String,
    private val baseUrl: String = provider.defaultBaseUrl,
    private val model: String = provider.defaultModel,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
) {

    fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        forceTool: String? = null
    ): Result<ChatMessage.Assistant> = runCatching {
        val claude = provider == AgentProvider.CLAUDE
        val body = if (claude) claudeBody(system, messages, tools, forceTool) else openAiBody(system, messages, tools, forceTool)
        val builder = Request.Builder()
            .url(if (claude) "$baseUrl/v1/messages" else "$baseUrl/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (claude) {
            builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
        } else if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                error("AI request failed (${response.code}): $message")
            }
            if (claude) claudeReply(JSONObject(text)) else openAiReply(JSONObject(text), forceTool)
        }
    }

    private fun claudeBody(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>, forceTool: String?): JSONObject {
        val out = JSONArray()
        var pendingResults: JSONArray? = null
        for (m in messages) {
            if (m is ChatMessage.ToolResult) {
                val results = pendingResults ?: JSONArray().also {
                    pendingResults = it
                    out.put(JSONObject().put("role", "user").put("content", it))
                }
                results.put(JSONObject().put("type", "tool_result").put("tool_use_id", m.callId).put("content", m.content))
                continue
            }
            pendingResults = null
            when (m) {
                is ChatMessage.User -> out.put(JSONObject().put("role", "user").put("content", m.text))
                is ChatMessage.Assistant -> {
                    val content = JSONArray()
                    if (m.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", m.text))
                    m.toolCalls.forEach {
                        content.put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name).put("input", it.input))
                    }
                    out.put(JSONObject().put("role", "assistant").put("content", content))
                }
                is ChatMessage.ToolResult -> Unit
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("tools", JSONArray(tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)
            }))
            .put("messages", out)
        forceTool?.let { body.put("tool_choice", JSONObject().put("type", "tool").put("name", it)) }
        return body
    }

    private fun claudeReply(response: JSONObject): ChatMessage.Assistant {
        val blocks = response.optJSONArray("content") ?: JSONArray()
        val all = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
        return ChatMessage.Assistant(
            text = all.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") },
            toolCalls = all.filter { it.optString("type") == "tool_use" }.map {
                ToolCall(it.optString("id"), it.optString("name"), it.optJSONObject("input") ?: JSONObject())
            }
        )
    }

    private fun openAiBody(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>, forceTool: String?): JSONObject {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in messages) out.put(when (m) {
            is ChatMessage.User -> JSONObject().put("role", "user").put("content", m.text)
            is ChatMessage.ToolResult -> JSONObject().put("role", "tool").put("tool_call_id", m.callId).put("content", m.content)
            is ChatMessage.Assistant -> JSONObject()
                .put("role", "assistant")
                .put("content", m.text.ifBlank { null } ?: JSONObject.NULL)
                .apply {
                    if (m.toolCalls.isNotEmpty()) put("tool_calls", JSONArray(m.toolCalls.map {
                        JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject()
                            .put("name", it.name)
                            .put("arguments", it.input.toString()))
                    }))
                }
        })
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("tools", JSONArray(tools.map {
                JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", it.name)
                    .put("description", it.description)
                    .put("parameters", it.schema))
            }))
            .put("messages", out)
        forceTool?.let {
            body.put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", it)))
        }
        return body
    }

    /** Falls back to a JSON object in the text for servers that ignore tool_choice. */
    private fun openAiReply(response: JSONObject, forceTool: String?): ChatMessage.Assistant {
        val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: error("AI returned no message")
        val text = if (message.isNull("content")) "" else message.optString("content")
        val calls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { call ->
                val fn = call.optJSONObject("function") ?: JSONObject()
                val input = runCatching { JSONObject(fn.optString("arguments")) }.getOrDefault(JSONObject())
                ToolCall(call.optString("id"), fn.optString("name"), input)
            }
        }.orEmpty()
        if (calls.isNotEmpty() || forceTool == null) return ChatMessage.Assistant(text, calls)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val input = if (start >= 0 && end > start) runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() else null
        return ChatMessage.Assistant(text, listOfNotNull(input?.let { ToolCall("fallback_${System.nanoTime()}", forceTool, it) }))
    }

    companion object {
        /** Properties of the `propose` tool input; the chat agent reuses them. Fresh copy per access. */
        val PROPOSE_FIELDS_SCHEMA: JSONObject get() {
            fun str() = JSONObject().put("type", "string")
            fun strArray() = JSONObject().put("type", "array").put("items", str())
            fun enum(vararg values: String) = str().put("enum", JSONArray(values.toList()))
            return JSONObject()
                .put("merchantName", str())
                .put("transactionType", enum("Expense", "Income", "Transfer-Out"))
                .put("account", str())
                .put("category", str())
                .put("subcategory", str())
                .put("notes", str())
                .put("participants", strArray())
                .put("splitMode", enum("equal", "exact", "percent", "weight"))
                .put("splitAllocations", JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", JSONObject().put("type", "number")))
                .put("warnings", strArray())
                .put("confidence", enum("high", "needs_attention"))
                .put("reason", str())
        }

        fun parseProposal(input: JSONObject): RawProposal {
            fun optStr(key: String) = if (input.has(key) && !input.isNull(key)) input.optString(key) else null
            fun strList(key: String): List<String>? = input.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }
            val allocations = input.optJSONObject("splitAllocations")?.let { obj ->
                obj.keys().asSequence().mapNotNull { key ->
                    obj.optString(key).toBigDecimalOrNull()?.let { key to it }
                }.toMap()
            }.orEmpty()
            return RawProposal(
                merchantName = optStr("merchantName"),
                transactionType = optStr("transactionType"),
                account = optStr("account"),
                category = optStr("category"),
                subcategory = optStr("subcategory"),
                notes = optStr("notes"),
                participants = strList("participants"),
                splitMode = optStr("splitMode"),
                splitAllocations = allocations,
                warnings = strList("warnings").orEmpty(),
                needsAttention = input.optString("confidence") == "needs_attention",
                reason = input.optString("reason")
            )
        }
    }
}

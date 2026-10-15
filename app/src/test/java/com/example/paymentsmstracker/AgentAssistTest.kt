package com.example.paymentsmstracker

import com.example.paymentsmstracker.agent.AgentClient
import com.example.paymentsmstracker.agent.AgentProvider
import com.example.paymentsmstracker.agent.AgentData
import com.example.paymentsmstracker.agent.AgentPrivacy
import com.example.paymentsmstracker.agent.ChatMessage
import com.example.paymentsmstracker.agent.ToolSpec
import com.example.paymentsmstracker.agent.FormState
import com.example.paymentsmstracker.agent.RawProposal
import com.example.paymentsmstracker.agent.validate
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AgentAssistTest {

    private val config = AppConfig()
    private val members = listOf("ME", "Puneet S", "Mukul")
    private val current = FormState(merchantName = "SWIGGY*ORDER", account = "SBI")

    @Test
    fun inventedValuesAreDroppedWithWarnings() {
        val s = RawProposal(
            account = "Chase Sapphire",
            category = "Groceries",
            participants = listOf("ME", "Elon"),
            merchantName = "Swiggy"
        ).validate(current, config, members, 840.0)

        assertEquals("SBI", s.after.account)
        assertEquals("", s.after.category)
        assertTrue(s.after.participants.isEmpty())
        assertEquals("Swiggy", s.after.merchantName)
        assertEquals(3, s.warnings.size)
    }

    @Test
    fun valuesAreMatchedCaseInsensitivelyToCanonicalNames() {
        val s = RawProposal(account = "hdfc CREDIT card", category = "food", subcategory = "dinner")
            .validate(current, config, members, 840.0)
        assertEquals("HDFC credit card", s.after.account)
        assertEquals("Food", s.after.category)
        assertEquals("Dinner", s.after.subcategory)
        assertTrue(s.warnings.isEmpty())
    }

    @Test
    fun percentSplitSharesComeFromSplitCalculator() {
        val s = RawProposal(
            participants = listOf("me", "puneet s"),
            splitMode = "percent",
            splitAllocations = mapOf("ME" to BigDecimal(60), "Puneet S" to BigDecimal(40))
        ).validate(current, config, members, 840.0)

        assertEquals(listOf("ME", "Puneet S"), s.after.participants)
        assertEquals(SplitMode.PERCENT, s.after.splitMode)
        val expected = SplitCalculator.resolve(840.0, listOf("ME", "Puneet S"), SplitMode.PERCENT, s.after.splitAllocations)
        assertEquals(expected, s.split)
        assertEquals(BigDecimal("504.00"), s.split!!.amounts["ME"])
        assertEquals(BigDecimal("336.00"), s.split!!.amounts["Puneet S"])
    }

    @Test
    fun invalidSplitIsRejectedAndFormSplitKept() {
        val s = RawProposal(
            participants = listOf("ME", "Mukul"),
            splitMode = "percent",
            splitAllocations = mapOf("ME" to BigDecimal(60), "Mukul" to BigDecimal(30))
        ).validate(current, config, members, 840.0)
        assertTrue(s.after.participants.isEmpty())
        assertNull(s.split)
        assertTrue(s.warnings.single().startsWith("Split ignored"))
        assertFalse(s.hasChanges)
    }

    @Test
    fun splitWithoutGroupMembersIsRejected() {
        val s = RawProposal(participants = listOf("ME", "Mukul"))
            .validate(current, config, emptyList(), 840.0)
        assertTrue(s.after.participants.isEmpty())
        assertEquals("Pick a Splitwise group before splitting", s.warnings.single())
    }

    @Test
    fun transferClearsSplitAndSubcategory() {
        val split = current.copy(category = "Food", subcategory = "Dinner", participants = listOf("ME", "Mukul"))
        val s = RawProposal(transactionType = "Transfer-Out", category = "Cash")
            .validate(split, config, members, 500.0)
        assertEquals("Transfer-Out", s.after.transactionType)
        assertEquals("Cash", s.after.category)
        assertEquals("", s.after.subcategory)
        assertTrue(s.after.participants.isEmpty())
    }

    @Test
    fun clientSendsMinimalForcedToolRequestAndParsesProposal() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"propose","input":{
                    "category":"Food","participants":["ME","Mukul"],"splitMode":"equal",
                    "confidence":"needs_attention","warnings":["Possible duplicate"],"reason":"Swiggy is food"}}]}"""
            ))
            val result = AgentClient(AgentProvider.CLAUDE, "sk-test", baseUrl = server.url("/").toString().trimEnd('/'))
                .let(::proposeVia)

            val raw = result.getOrThrow()
            assertEquals("Food", raw.category)
            assertEquals(listOf("ME", "Mukul"), raw.participants)
            assertTrue(raw.needsAttention)
            assertNull(raw.account)

            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertEquals("sk-test", recorded.getHeader("x-api-key"))
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("propose", body.getJSONObject("tool_choice").getString("name"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun txJsonLeavesOutWhatThePrivacySwitchesTurnOff() {
        val tx = Transaction(
            id = 7, amount = 840.0, merchantName = "Swiggy", timestamp = 1L, notes = "n",
            originalSms = "Rs 840 debited", referenceNumber = "REF123", latitude = 12.9, longitude = 77.6
        )
        val defaults = AgentData.txJson(tx, AgentPrivacy()).toString()
        assertTrue(defaults.contains("Swiggy") && defaults.contains("840"))
        assertFalse(defaults.contains("Rs 840 debited") || defaults.contains("12.9") || defaults.contains("REF123"))

        val all = AgentData.txJson(tx, AgentPrivacy(sms = true, location = true)).toString()
        assertTrue(all.contains("Rs 840 debited") && all.contains("12.9"))
        assertFalse(all.contains("REF123"))

        val none = AgentData.txJson(tx, AgentPrivacy(amounts = false, merchants = false, dates = false, notes = false))
        assertFalse(none.has("amount") || none.has("merchantName") || none.has("date") || none.has("notes"))
    }

    @Test
    fun openAiCompatibleUsesChatCompletionsToolCallsAndTextFallback() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function",
                    "function":{"name":"propose","arguments":"{\"category\":\"Food\",\"confidence\":\"high\",\"reason\":\"r\"}"}}]}}]}"""
            ))
            // Local servers that ignore tool_choice answer in text.
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Sure: {\"account\":\"Cash\",\"confidence\":\"high\",\"reason\":\"r\"}"}}]}"""
            ))
            val client = AgentClient(AgentProvider.OPENROUTER, "or-key", server.url("/api/v1").toString(), "some/model")

            assertEquals("Food", proposeVia(client).getOrThrow().category)
            val recorded = server.takeRequest()
            assertEquals("/api/v1/chat/completions", recorded.path)
            assertEquals("Bearer or-key", recorded.getHeader("Authorization"))
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("some/model", body.getString("model"))
            assertEquals("propose", body.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
            assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))

            assertEquals("Cash", proposeVia(client).getOrThrow().account)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiCompatibleWithoutKeySendsNoAuthHeader() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"no json here"}}]}"""))
            val result = proposeVia(AgentClient(AgentProvider.OPENAI_COMPATIBLE, "", server.url("/v1").toString()))
            assertEquals("AI returned no proposal", result.exceptionOrNull()?.message)
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpErrorAndMissingToolUseAreFailures() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"overloaded"}}"""))
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"hi"}]}"""))
            val client = AgentClient(AgentProvider.CLAUDE, "sk-test", baseUrl = server.url("/").toString().trimEnd('/'))

            val first = proposeVia(client).exceptionOrNull()
            assertTrue(first!!.message!!.contains("overloaded"))
            assertEquals("AI returned no proposal", proposeVia(client).exceptionOrNull()?.message)
        } finally {
            server.shutdown()
        }
    }

    private val lookupTool = ToolSpec("lookup", "Find transactions.", JSONObject().put("type", "object"))

    @Test
    fun claudeChatSendsToolUseAndToolResultOnSecondTurn() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"content":[{"type":"text","text":"Checking."},{"type":"tool_use","id":"tu1","name":"lookup","input":{"q":"swiggy"}}]}"""
            ))
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"You spent 840."}]}"""))
            val client = AgentClient(AgentProvider.CLAUDE, "sk-test", baseUrl = server.url("/").toString().trimEnd('/'))

            val history = mutableListOf<ChatMessage>(ChatMessage.User("swiggy spend?"))
            val first = client.chat("sys", history, listOf(lookupTool)).getOrThrow()
            assertEquals("Checking.", first.text)
            assertEquals("tu1", first.toolCalls.single().id)
            assertEquals("swiggy", first.toolCalls.single().input.getString("q"))
            history += first
            history += ChatMessage.ToolResult("tu1", "[840]")
            assertEquals("You spent 840.", client.chat("sys", history, listOf(lookupTool)).getOrThrow().text)

            val firstBody = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(firstBody.has("tool_choice"))
            assertEquals("lookup", firstBody.getJSONArray("tools").getJSONObject(0).getString("name"))
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(3, messages.length())
            val assistant = messages.getJSONObject(1)
            assertEquals("assistant", assistant.getString("role"))
            val toolUse = assistant.getJSONArray("content").getJSONObject(1)
            assertEquals("tool_use", toolUse.getString("type"))
            assertEquals("tu1", toolUse.getString("id"))
            assertEquals("swiggy", toolUse.getJSONObject("input").getString("q"))
            val result = messages.getJSONObject(2)
            assertEquals("user", result.getString("role"))
            val block = result.getJSONArray("content").getJSONObject(0)
            assertEquals("tool_result", block.getString("type"))
            assertEquals("tu1", block.getString("tool_use_id"))
            assertEquals("[840]", block.getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiChatSendsToolRoleMessageOnSecondTurn() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",
                    "function":{"name":"lookup","arguments":"{\"q\":\"swiggy\"}"}}]}}]}"""
            ))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"You spent 840."}}]}"""))
            val client = AgentClient(AgentProvider.OPENROUTER, "or-key", server.url("/api/v1").toString(), "some/model")

            val history = mutableListOf<ChatMessage>(ChatMessage.User("swiggy spend?"))
            val first = client.chat("sys", history, listOf(lookupTool)).getOrThrow()
            assertEquals("", first.text)
            assertEquals("swiggy", first.toolCalls.single().input.getString("q"))
            history += first
            history += ChatMessage.ToolResult("c1", "[840]")
            val second = client.chat("sys", history, listOf(lookupTool)).getOrThrow()
            assertEquals("You spent 840.", second.text)
            assertTrue(second.toolCalls.isEmpty())

            server.takeRequest()
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(listOf("system", "user", "assistant", "tool"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
            val call = messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("c1", call.getString("id"))
            assertEquals("swiggy", JSONObject(call.getJSONObject("function").getString("arguments")).getString("q"))
            val tool = messages.getJSONObject(3)
            assertEquals("c1", tool.getString("tool_call_id"))
            assertEquals("[840]", tool.getString("content"))
        } finally {
            server.shutdown()
        }
    }

    /** What the old one-shot propose() did: force the `propose` tool and parse it. */
    private fun proposeVia(client: AgentClient) = client.chat(
        "sys", listOf(ChatMessage.User("u")), listOf(ToolSpec("propose", "d", JSONObject().put("type", "object"))), forceTool = "propose"
    ).mapCatching { reply ->
        AgentClient.parseProposal(reply.toolCalls.firstOrNull { it.name == "propose" }?.input ?: error("AI returned no proposal"))
    }
}

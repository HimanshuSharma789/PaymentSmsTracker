package com.example.paymentsmstracker.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import com.example.paymentsmstracker.splitwise.SplitwiseMemberLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CardState { PENDING, APPLIED, SKIPPED, FAILED }

sealed class ChatItem {
    data class UserText(val text: String) : ChatItem()
    data class AssistantText(val text: String) : ChatItem()
    data class Error(val text: String) : ChatItem()
    data class Card(
        val proposal: Proposal,
        val state: CardState = CardState.PENDING,
        val message: String? = null,
        /** (old, new) after a successful Apply. */
        val applied: Pair<Transaction, Transaction>? = null
    ) : ChatItem()
}

class AgentChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val history = mutableListOf<ChatMessage>()
    private val _items = MutableLiveData<List<ChatItem>>(emptyList())
    val items: LiveData<List<ChatItem>> = _items
    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    /** Transaction the chat was opened from; 0 = none. */
    var seedTransactionId = 0L

    fun send(text: String) {
        if (_busy.value == true || text.isBlank()) return
        // Every turn re-sends the whole history (tool results included), so cost grows with it.
        // Keep the last few exchanges; cutting at a user message keeps tool calls paired.
        val userTurns = history.indices.filter { history[it] is ChatMessage.User }
        if (userTurns.size >= MAX_EXCHANGES) {
            history.subList(0, userTurns[userTurns.size - MAX_EXCHANGES + 1]).clear()
        }
        val start = history.size
        history += ChatMessage.User(text.trim())
        add(ChatItem.UserText(text.trim()))
        _busy.value = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val settings = AgentSettings(app)
                val client = settings.client()
                val privacy = settings.privacy
                val config = ConfigStore(app).load()
                val auth = SplitwiseAuthStore(app).load()
                val tools = AgentTools(db, config, privacy, auth)
                val defaultLabels = auth.groupById(auth.defaultGroupId)
                    ?.let { g -> SplitwiseMemberLabels.labelsFor(g.members, auth.currentUserId).map { it.first } }.orEmpty()
                val seed = seedTransactionId.takeIf { it > 0L }
                    ?.let { withContext(Dispatchers.IO) { db.transactionDao().getTransactionById(it) } }
                val system = buildString {
                    append(SYSTEM.replace("{today}", SimpleDateFormat("yyyy-MM-dd EEE", Locale.US).format(Date())))
                    append("\nOptions: ").append(AgentData.optionsJson(config, defaultLabels, privacy))
                    seed?.let { append("\nThe user opened chat from this transaction: ").append(AgentData.txJson(it, privacy)) }
                }
                var shown = 0
                repeat(MAX_TURNS) {
                    val reply = withContext(Dispatchers.IO) { client.chat(system, history.toList(), tools.specs) }
                        .getOrElse { e ->
                            history.subList(start, history.size).clear()
                            add(ChatItem.Error(e.message ?: "AI request failed"))
                            return@launch
                        }
                    if (reply.text.isBlank() && reply.toolCalls.isEmpty()) {
                        // Not stored: an empty assistant turn would make every later request fail.
                        add(ChatItem.Error("The AI sent an empty reply. Try rephrasing."))
                        return@launch
                    }
                    history += reply
                    if (reply.text.isNotBlank()) add(ChatItem.AssistantText(reply.text.trim()))
                    if (reply.toolCalls.isEmpty()) return@launch
                    for (call in reply.toolCalls) {
                        history += ChatMessage.ToolResult(call.id, withContext(Dispatchers.IO) { tools.execute(call) })
                    }
                    tools.proposals.drop(shown).forEach { add(ChatItem.Card(it)) }
                    shown = tools.proposals.size
                }
                add(ChatItem.Error("Stopped after $MAX_TURNS steps. Ask again to continue."))
            } finally {
                _busy.value = false
            }
        }
    }

    fun apply(card: ChatItem.Card) {
        viewModelScope.launch { applyNow(card) }
    }

    fun applyAll() {
        viewModelScope.launch {
            current().filterIsInstance<ChatItem.Card>().filter { it.state == CardState.PENDING }.forEach { applyNow(it) }
        }
    }

    fun skip(card: ChatItem.Card) = replace(card.copy(state = CardState.SKIPPED))

    fun undo(card: ChatItem.Card) {
        val (old, new) = card.applied ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AgentApplier.undo(db, old, new) }
                .onSuccess { replace(card.copy(state = CardState.PENDING, applied = null, message = null)) }
                .onFailure { replace(card.copy(message = it.message)) }
        }
    }

    private suspend fun applyNow(card: ChatItem.Card) {
        withContext(Dispatchers.IO) { AgentApplier.apply(db, card.proposal) }
            .onSuccess { replace(card.copy(state = CardState.APPLIED, applied = it, message = null)) }
            .onFailure { replace(card.copy(state = CardState.FAILED, message = it.message)) }
    }

    private fun current() = _items.value.orEmpty()

    private fun add(item: ChatItem) {
        _items.value = current() + item
    }

    private fun replace(card: ChatItem.Card) {
        _items.value = current().map { if (it is ChatItem.Card && it.proposal.key == card.proposal.key) card else it }
    }

    companion object {
        private const val MAX_TURNS = 8
        private const val MAX_EXCHANGES = 6

        private val SYSTEM = """
            You are the assistant inside a personal expense tracker (amounts in INR). Today is {today}.
            Rules:
            - You never write data. To change transactions, call propose_update; the user sees a diff card and taps Apply.
              Group ids that get the same changes into one call.
            - Never add, subtract or compute money yourself. Use summarize or friend_shares and quote their numbers.
            - account, category, subcategory and participants must be copied exactly from the allowed lists.
              "ME" is the user. Transfer-Out: category is the destination account; no subcategory, no split.
            - Splits: give participants and splitMode; for non-equal modes give splitAllocations exactly as the user
              stated them. The app does the math. participants: [] removes a split.
            - "Pending" or "to review" means status needs_review.
            - Read each propose_update result; fix rejected ids or warnings when you can.
            - Reply briefly in plain text, no markdown tables.
        """.trimIndent()
    }
}

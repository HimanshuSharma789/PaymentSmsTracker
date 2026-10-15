package com.example.paymentsmstracker

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.agent.AgentChatViewModel
import com.example.paymentsmstracker.agent.AgentSettings
import com.example.paymentsmstracker.agent.CardState
import com.example.paymentsmstracker.agent.ChatItem
import com.example.paymentsmstracker.data.ReviewStatus
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentChatActivity : AppCompatActivity() {

    private val viewModel: AgentChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_chat)
        findViewById<MaterialToolbar>(R.id.toolbarAgentChat).setNavigationOnClickListener { finish() }
        viewModel.seedTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)

        val recycler = findViewById<RecyclerView>(R.id.recyclerAgentChat)
        val adapter = ChatAdapter(viewModel)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        val input = findViewById<EditText>(R.id.editAgentInput)
        val send = findViewById<MaterialButton>(R.id.buttonAgentSend)
        val empty = findViewById<View>(R.id.layoutAgentEmpty)
        val applyAll = findViewById<MaterialButton>(R.id.buttonAgentApplyAll)
        val chips = findViewById<ChipGroup>(R.id.chipGroupAgentExamples)

        if (!AgentSettings(this).isReady) {
            findViewById<TextView>(R.id.textAgentEmpty).setText(R.string.agent_chat_not_ready)
            input.isEnabled = false
            send.isEnabled = false
            return
        }

        resources.getStringArray(R.array.agent_chat_examples).forEach { example ->
            chips.addView(Chip(this).apply {
                text = example
                setOnClickListener { input.setText(example); input.setSelection(example.length) }
            })
        }
        fun submit() {
            val text = input.text.toString()
            if (text.isBlank() || viewModel.busy.value == true) return
            input.text = null
            viewModel.send(text)
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
            submit()
            true
        }
        applyAll.setOnClickListener { viewModel.applyAll() }

        viewModel.items.observe(this) { items ->
            adapter.submit(items)
            if (items.isNotEmpty()) recycler.scrollToPosition(items.size - 1)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            val pending = items.count { it is ChatItem.Card && it.state == CardState.PENDING }
            applyAll.visibility = if (pending > 1) View.VISIBLE else View.GONE
            applyAll.text = getString(R.string.agent_chat_apply_all, pending)
        }
        viewModel.busy.observe(this) { busy ->
            findViewById<View>(R.id.progressAgentChat).visibility = if (busy) View.VISIBLE else View.GONE
            send.isEnabled = !busy
        }
    }

    private class ChatAdapter(private val vm: AgentChatViewModel) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<ChatItem> = emptyList()

        fun submit(list: List<ChatItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is ChatItem.UserText -> USER
            is ChatItem.Card -> CARD
            else -> ASSISTANT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == CARD) R.layout.item_agent_proposal else R.layout.item_agent_message
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ChatItem.UserText -> bindMessage(holder.itemView, item.text, Gravity.END,
                    com.google.android.material.R.attr.colorPrimaryContainer)
                is ChatItem.AssistantText -> bindMessage(holder.itemView, item.text, Gravity.START,
                    com.google.android.material.R.attr.colorSurfaceVariant)
                is ChatItem.Error -> bindMessage(holder.itemView, item.text, Gravity.START,
                    com.google.android.material.R.attr.colorErrorContainer)
                is ChatItem.Card -> bindCard(holder.itemView, item)
            }
        }

        private fun bindMessage(view: View, text: String, gravity: Int, colorAttr: Int) {
            val card = view.findViewById<MaterialCardView>(R.id.cardAgentMessage)
            (card.layoutParams as FrameLayout.LayoutParams).gravity = gravity
            card.setCardBackgroundColor(MaterialColors.getColor(card, colorAttr))
            view.findViewById<TextView>(R.id.textAgentMessage).text = text
        }

        private fun bindCard(view: View, card: ChatItem.Card) {
            val p = card.proposal
            val s = p.suggestion
            val ctx = view.context
            view.findViewById<TextView>(R.id.textProposalHeader).text = listOf(
                p.tx.merchantName,
                NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(p.tx.amount),
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(p.tx.timestamp))
            ).joinToString(" · ")
            view.findViewById<TextView>(R.id.textProposalDiff).text = buildList {
                s.diff().forEach { (label, before, after) -> add("$label: $before → $after") }
                if (p.markReviewed && p.tx.reviewStatus == ReviewStatus.NEEDS_REVIEW) add(ctx.getString(R.string.agent_chat_mark_reviewed))
            }.joinToString("\n")
            view.findViewById<TextView>(R.id.textProposalSplit).showText(
                s.split?.takeIf { it.isValid && s.after.participants.isNotEmpty() }
                    ?.amounts?.entries?.joinToString(" · ") { "${it.key} ₹${it.value.toPlainString()}" }
            )
            view.findViewById<TextView>(R.id.textProposalWarnings).showText(
                s.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { "⚠ $it" }
            )
            view.findViewById<TextView>(R.id.textProposalReason).showText(s.reason.ifBlank { null })
            view.findViewById<TextView>(R.id.textProposalState).text = listOfNotNull(
                when (card.state) {
                    CardState.APPLIED -> ctx.getString(R.string.agent_chat_applied)
                    CardState.SKIPPED -> ctx.getString(R.string.agent_chat_skipped)
                    CardState.FAILED -> ctx.getString(R.string.agent_chat_failed)
                    CardState.PENDING -> null
                },
                card.message
            ).joinToString(": ")

            val pending = card.state == CardState.PENDING
            view.findViewById<MaterialButton>(R.id.buttonProposalSkip).apply {
                visibility = if (pending) View.VISIBLE else View.GONE
                setOnClickListener { vm.skip(card) }
            }
            view.findViewById<MaterialButton>(R.id.buttonProposalApply).apply {
                visibility = if (pending || card.state == CardState.APPLIED) View.VISIBLE else View.GONE
                setText(if (pending) R.string.agent_chat_apply else R.string.agent_chat_undo)
                setOnClickListener { if (pending) vm.apply(card) else vm.undo(card) }
            }
        }

        private fun TextView.showText(value: String?) {
            text = value
            visibility = if (value == null) View.GONE else View.VISIBLE
        }

        companion object {
            private const val USER = 0
            private const val ASSISTANT = 1
            private const val CARD = 2
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
    }
}

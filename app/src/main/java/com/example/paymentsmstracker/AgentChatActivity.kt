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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.agent.AgentChatViewModel
import com.example.paymentsmstracker.agent.AgentProvider
import com.example.paymentsmstracker.agent.AgentSettings
import com.example.paymentsmstracker.agent.CardState
import com.example.paymentsmstracker.agent.ChatItem
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.home.Money
import com.example.paymentsmstracker.ui.MonoNav
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.content.ContextCompat
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentChatActivity : AppCompatActivity() {

    private val viewModel: AgentChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_chat)
        MonoNav.bind(this, MonoNav.Tab.ASK)
        val settings = AgentSettings(this)
        bindHeader(settings)
        viewModel.seedTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)

        val recycler = findViewById<RecyclerView>(R.id.recyclerAgentChat)
        val adapter = ChatAdapter(viewModel)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        // Keep the last message clear of the pinned composer (its height changes with Apply all).
        findViewById<View>(R.id.agentComposer).addOnLayoutChangeListener { v, _, top, _, _, _, _, _, _ ->
            val root = v.parent as View
            val pad = root.height - root.paddingBottom - top + (16 * resources.displayMetrics.density).toInt()
            if (recycler.paddingBottom != pad) recycler.post {
                recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, pad)
            }
        }

        val input = findViewById<EditText>(R.id.editAgentInput)
        val send = findViewById<MaterialButton>(R.id.buttonAgentSend)
        val empty = findViewById<View>(R.id.layoutAgentEmpty)
        val applyAll = findViewById<MaterialButton>(R.id.buttonAgentApplyAll)
        val chips = findViewById<ChipGroup>(R.id.chipGroupAgentExamples)

        if (!settings.isReady) {
            findViewById<TextView>(R.id.textAgentEmpty).setText(R.string.agent_chat_not_ready)
            input.isEnabled = false
            send.isEnabled = false
            return
        }

        resources.getStringArray(R.array.agent_chat_examples).forEach { example ->
            chips.addView(Chip(this).apply {
                text = example
                isCheckable = false
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
        applyAll.setOnClickListener {
            val pending = viewModel.items.value.orEmpty()
                .filterIsInstance<ChatItem.Card>().filter { it.state == CardState.PENDING }
            val freeText = pending.count { it.proposal.suggestion.changesFreeText }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.agent_chat_apply_all, pending.size))
                .setMessage(
                    if (freeText > 0) getString(R.string.agent_chat_apply_all_free_text, freeText)
                    else getString(R.string.agent_chat_apply_all_confirm)
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.applyAll() }
                .show()
        }

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

    /** Provider tag ("Your key · Claude") and what the model may read, from AgentSettings. */
    private fun bindHeader(settings: AgentSettings) {
        val provider = settings.provider
        val name = getString(
            when (provider) {
                AgentProvider.CLAUDE -> R.string.ai_provider_claude
                AgentProvider.OPENROUTER -> R.string.ai_provider_openrouter
                AgentProvider.OPENAI_COMPATIBLE -> R.string.ai_provider_openai
            }
        )
        findViewById<TextView>(R.id.textAgentProvider).text = when {
            !settings.enabled -> getString(R.string.agent_mono_off)
            settings.apiKey(provider).isNotBlank() -> getString(R.string.agent_mono_your_key, name)
            else -> name
        }
        val p = settings.privacy
        val fields = listOf(
            p.amounts to R.string.ai_read_amounts,
            p.merchants to R.string.ai_read_merchants,
            p.dates to R.string.ai_read_dates,
            p.categories to R.string.ai_read_categories,
            p.notes to R.string.ai_read_notes,
            p.friends to R.string.ai_read_friends,
            p.merchantMemory to R.string.ai_read_merchant_memory,
            p.history to R.string.ai_read_history,
            p.sms to R.string.ai_read_sms,
            p.location to R.string.ai_read_location
        ).filter { it.first }.map { getString(it.second).lowercase(Locale.getDefault()) }
        val reads = if (fields.isEmpty()) getString(R.string.agent_mono_reads_nothing)
        else getString(
            R.string.agent_mono_reads,
            if (fields.size == 1) fields[0] else fields.dropLast(1).joinToString(", ") + " " + getString(R.string.agent_mono_and) + " " + fields.last()
        )
        findViewById<TextView>(R.id.textAgentReads).text = "$reads ${getString(R.string.agent_mono_never_sent)}"
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
                is ChatItem.UserText -> bindMessage(holder.itemView, item.text, user = true, textColor = R.color.mono_on_ink)
                is ChatItem.AssistantText -> bindMessage(holder.itemView, item.text, user = false, textColor = R.color.mono_ink, reportable = true)
                is ChatItem.Error -> bindMessage(holder.itemView, item.text, user = false, textColor = R.color.mono_error)
                is ChatItem.Card -> bindCard(holder.itemView, item)
            }
        }

        /** User: ink bubble on the right with a tucked corner. Assistant/error: full-width white card. */
        private fun bindMessage(view: View, text: String, user: Boolean, textColor: Int, reportable: Boolean = false) {
            val reply = text
            val ctx = view.context
            val d = ctx.resources.displayMetrics.density
            val card = view.findViewById<MaterialCardView>(R.id.cardAgentMessage)
            (card.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (user) Gravity.END else Gravity.START
                width = if (user) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                marginStart = if (user) (48 * d).toInt() else 0
            }
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, if (user) R.color.mono_ink else R.color.mono_surface))
            card.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes((if (user) 22 else 20) * d)
                .apply { if (user) setBottomRightCornerSize(6 * d) }
                .build()
            view.findViewById<TextView>(R.id.textAgentMessage).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(ctx, textColor))
            }
            view.findViewById<MaterialButton>(R.id.buttonReportResponse).apply {
                visibility = if (reportable) View.VISIBLE else View.GONE
                setOnClickListener { Disclosures.reportResponse(ctx, reply) }
            }
        }

        private fun bindCard(view: View, card: ChatItem.Card) {
            val p = card.proposal
            val s = p.suggestion
            val ctx = view.context
            val merchant = p.tx.merchantName.ifBlank { "Payment" }
            view.findViewById<TextView>(R.id.textProposalMonogram).text = merchant.take(1).uppercase()
            view.findViewById<TextView>(R.id.textProposalHeader).text = merchant
            view.findViewById<TextView>(R.id.textProposalMeta).text = listOf(
                SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(p.tx.timestamp)),
                p.tx.account
            ).filter { it.isNotBlank() }.joinToString(" · ")
            view.findViewById<TextView>(R.id.textProposalAmount).text = Money.styled(
                Money.format(java.math.BigDecimal.valueOf(p.tx.amount), withPaise = p.tx.amount % 1.0 != 0.0)
            )
            view.findViewById<TextView>(R.id.textProposalDiff).text = buildList {
                s.diff().forEach { (label, before, after) -> add("$label: $before → $after") }
                if (p.markReviewed && p.tx.reviewStatus == ReviewStatus.NEEDS_REVIEW) add(ctx.getString(R.string.agent_chat_mark_reviewed))
            }.joinToString("\n")
            view.findViewById<TextView>(R.id.textProposalSplit).showText(
                s.split?.takeIf { it.isValid && s.after.participants.isNotEmpty() }
                    ?.amounts?.entries?.joinToString(" · ") { "${it.key} ₹${it.value.toPlainString()}" }
            )
            view.findViewById<TextView>(R.id.textProposalWarnings).showText(
                (s.warnings + listOfNotNull(
                    ctx.getString(R.string.agent_chat_free_text_warning).takeIf { s.changesFreeText }
                )).takeIf { it.isNotEmpty() }?.joinToString("\n") { "⚠ $it" }
            )
            view.findViewById<TextView>(R.id.textProposalReason).showText(s.reason.ifBlank { null })
            view.findViewById<TextView>(R.id.textProposalState).showText(listOfNotNull(
                when (card.state) {
                    CardState.APPLIED -> ctx.getString(R.string.agent_chat_applied)
                    CardState.SKIPPED -> ctx.getString(R.string.agent_chat_skipped)
                    CardState.FAILED -> ctx.getString(R.string.agent_chat_failed)
                    CardState.PENDING -> null
                },
                card.message
            ).joinToString(": ").ifBlank { null })

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
            text = value?.let { Money.styled(it) }
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

package com.example.paymentsmstracker.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.data.SplitwiseExpenseIds
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SplitRowUi(
    val id: Long,
    val date: String,
    val merchant: String,
    val total: String,
    val myShare: String,
    val friendsOwe: String,
    val people: String,
    val pushed: Boolean,
    val manualSuccess: Boolean,
    val error: String,
    val failed: Boolean
)

class SplitTableAdapter(
    private val onOpen: (Long) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<SplitRowUi, SplitTableAdapter.VH>(Diff) {

    private var selectionMode = false
    private val selected = linkedSetOf<Long>()
    private var allowSelection = true

    object Diff : DiffUtil.ItemCallback<SplitRowUi>() {
        override fun areItemsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem == newItem
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardSplitRow)
        val check: MaterialCheckBox = view.findViewById(R.id.checkSplitSelect)
        val merchant: TextView = view.findViewById(R.id.textSplitMerchant)
        val meta: TextView = view.findViewById(R.id.textSplitMeta)
        val breakdown: TextView = view.findViewById(R.id.textSplitBreakdown)
        val friendsOwe: TextView = view.findViewById(R.id.textSplitFriendsOwe)
        val status: TextView = view.findViewById(R.id.textSplitStatus)
        val error: TextView = view.findViewById(R.id.textSplitError)
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedIds(): Set<Long> = selected.toSet()

    fun setAllowSelection(allow: Boolean) {
        allowSelection = allow
        if (!allow && selectionMode) {
            exitSelectionMode()
        }
    }

    fun enterSelectionMode(initialId: Long? = null) {
        if (!allowSelection) return
        selectionMode = true
        if (initialId != null) selected.add(initialId)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAllVisible() {
        selected.clear()
        currentList.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_split_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.merchant.text = row.merchant
        holder.meta.text = buildString {
            append(row.date)
            if (row.people.isNotBlank()) {
                append(" · ")
                append(row.people)
            }
        }
        holder.breakdown.text =
            context.getString(R.string.split_row_breakdown, row.total, row.myShare)
        holder.friendsOwe.text = context.getString(R.string.split_row_amount, row.friendsOwe)

        val statusLabel: Int
        val statusBackground: Int
        val statusForeground: Int
        when {
            row.failed -> {
                statusLabel = R.string.split_status_failed
                statusBackground = R.color.split_status_failed_bg
                statusForeground = R.color.split_status_failed_fg
            }
            row.manualSuccess -> {
                statusLabel = R.string.split_status_marked_done
                statusBackground = R.color.split_status_pushed_bg
                statusForeground = R.color.split_status_pushed_fg
            }
            row.pushed -> {
                statusLabel = R.string.split_status_pushed
                statusBackground = R.color.split_status_pushed_bg
                statusForeground = R.color.split_status_pushed_fg
            }
            else -> {
                statusLabel = R.string.split_status_pending
                statusBackground = R.color.split_status_pending_bg
                statusForeground = R.color.split_status_pending_fg
            }
        }
        holder.status.setText(statusLabel)
        holder.status.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, statusBackground))
        holder.status.setTextColor(ContextCompat.getColor(context, statusForeground))

        holder.error.visibility = if (row.error.isBlank()) View.GONE else View.VISIBLE
        holder.error.text = row.error

        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = selected.contains(row.id)
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(row.id) else selected.remove(row.id)
            applySelectionStroke(holder, row.id)
            onSelectionChanged(selected.size)
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                holder.check.isChecked = !holder.check.isChecked
            } else {
                onOpen(row.id)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!allowSelection) {
                false
            } else if (!selectionMode) {
                enterSelectionMode(row.id)
                true
            } else {
                false
            }
        }
        applySelectionStroke(holder, row.id)
    }

    private fun applySelectionStroke(holder: VH, id: Long) {
        holder.card.setStrokeColor(
            MaterialColors.getColor(
                holder.card,
                if (selectionMode && selected.contains(id)) {
                    com.google.android.material.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOutlineVariant
                }
            )
        )
    }

    companion object {
        fun fromTransaction(tx: Transaction): SplitRowUi {
            val people = tx.participantList()
            val total = BigDecimal.valueOf(tx.amount).setScale(2, RoundingMode.HALF_UP)
            val split = SplitCalculator.resolveOrEqual(tx)
            val myShare = split.myShare
            val friends = split.friendsOwe
            val date = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(tx.timestamp))
            val modeLabel = SplitMode.label(tx.splitMode)
            val peopleLabel = buildString {
                append(people.joinToString(", "))
                if (modeLabel != "Equal") {
                    append(" · ")
                    append(modeLabel)
                }
            }
            val failed = tx.splitwisePushError.isNotBlank()
            return SplitRowUi(
                id = tx.id,
                date = date,
                merchant = tx.merchantName,
                total = total.toPlainString(),
                myShare = myShare.toPlainString(),
                friendsOwe = friends.toPlainString(),
                people = peopleLabel,
                pushed = SplitwiseExpenseIds.isDone(tx.splitwiseExpenseId),
                manualSuccess = SplitwiseExpenseIds.isManualSuccess(tx.splitwiseExpenseId),
                error = tx.splitwisePushError,
                failed = failed && !SplitwiseExpenseIds.isDone(tx.splitwiseExpenseId)
            )
        }
    }
}

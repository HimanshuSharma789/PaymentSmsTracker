package com.example.paymentsmstracker.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.google.android.material.checkbox.MaterialCheckBox
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
    val status: String,
    val failed: Boolean
)

class SplitTableAdapter(
    private val onOpen: (Long) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<SplitRowUi, SplitTableAdapter.VH>(Diff) {

    private var selectionMode = false
    private val selected = linkedSetOf<Long>()

    object Diff : DiffUtil.ItemCallback<SplitRowUi>() {
        override fun areItemsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem == newItem
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val check: MaterialCheckBox = view.findViewById(R.id.checkSplitSelect)
        val date: TextView = view.findViewById(R.id.textSplitDate)
        val merchant: TextView = view.findViewById(R.id.textSplitMerchant)
        val total: TextView = view.findViewById(R.id.textSplitTotal)
        val myShare: TextView = view.findViewById(R.id.textSplitMyShare)
        val friendsOwe: TextView = view.findViewById(R.id.textSplitFriendsOwe)
        val people: TextView = view.findViewById(R.id.textSplitPeople)
        val status: TextView = view.findViewById(R.id.textSplitStatus)
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedIds(): Set<Long> = selected.toSet()

    fun enterSelectionMode(initialId: Long? = null) {
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
        holder.date.text = row.date
        holder.merchant.text = row.merchant
        holder.total.text = row.total
        holder.myShare.text = row.myShare
        holder.friendsOwe.text = row.friendsOwe
        holder.people.text = row.people
        holder.status.text = row.status
        holder.status.setTextColor(
            if (row.failed) {
                holder.itemView.context.getColor(android.R.color.holo_red_dark)
            } else {
                holder.itemView.context.getColor(android.R.color.darker_gray)
            }
        )

        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = selected.contains(row.id)
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(row.id) else selected.remove(row.id)
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
            if (!selectionMode) {
                enterSelectionMode(row.id)
                true
            } else {
                false
            }
        }
        holder.itemView.setBackgroundColor(if (position % 2 == 0) 0x00000000 else 0x12000000)
    }

    companion object {
        fun fromTransaction(tx: Transaction): SplitRowUi {
            val people = tx.participantList()
            val total = BigDecimal.valueOf(tx.amount).setScale(2, RoundingMode.HALF_UP)
            val split = SplitCalculator.resolve(tx)
            val myShare = if (split.isValid) {
                split.myShare
            } else if (people.isEmpty()) {
                total
            } else {
                val share = total.divide(BigDecimal(people.size), 2, RoundingMode.HALF_UP)
                if (people.any { it.equals("ME", true) }) share else BigDecimal.ZERO.setScale(2)
            }
            val friends = if (split.isValid) split.friendsOwe else total.subtract(myShare)
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
            val status = when {
                tx.splitwiseExpenseId > 0L -> "Pushed"
                failed -> "Failed · ${tx.splitwisePushError}"
                else -> "Pending"
            }
            return SplitRowUi(
                id = tx.id,
                date = date,
                merchant = tx.merchantName,
                total = total.toPlainString(),
                myShare = myShare.toPlainString(),
                friendsOwe = friends.toPlainString(),
                people = peopleLabel,
                status = status,
                failed = failed
            )
        }
    }
}

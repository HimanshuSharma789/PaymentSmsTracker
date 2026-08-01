package com.example.paymentsmstracker.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface OnTransactionItemClickListener {
    fun onTransactionItemClick(transactionId: Long)
    fun onTransactionItemLongClick(transactionId: Long): Boolean
    fun onSelectionChanged(selectedCount: Int)
}

class TransactionAdapter(
    private val itemClickListener: OnTransactionItemClickListener
) : ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(TransactionDiffCallback()) {

    private var selectionMode = false
    private val selectedIds = linkedSetOf<Long>()
    private var listFilter: ListFilter = ListFilter.NEEDS_REVIEW

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun setListFilter(filter: ListFilter) {
        if (listFilter == filter) return
        listFilter = filter
        notifyDataSetChanged()
    }

    fun enterSelectionMode(initialId: Long? = null) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
        }
        if (initialId != null) {
            selectedIds.add(initialId)
        }
        notifyDataSetChanged()
        itemClickListener.onSelectionChanged(selectedIds.size)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        itemClickListener.onSelectionChanged(0)
    }

    fun selectAllVisible() {
        selectedIds.clear()
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        itemClickListener.onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        itemClickListener.onSelectionChanged(0)
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
        } else {
            selectedIds.add(id)
        }
        notifyDataSetChanged()
        itemClickListener.onSelectionChanged(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction = getItem(position)
        holder.bind(transaction)
        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(transaction.id)
            } else {
                itemClickListener.onTransactionItemClick(transaction.id)
            }
        }
        holder.itemView.setOnLongClickListener {
            itemClickListener.onTransactionItemLongClick(transaction.id)
        }
    }

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val merchantTextView: TextView = itemView.findViewById(R.id.textViewMerchant)
        private val amountTextView: TextView = itemView.findViewById(R.id.textViewAmount)
        private val groupDateTextView: TextView = itemView.findViewById(R.id.textViewGroupDate)
        private val categoryTextView: TextView = itemView.findViewById(R.id.textViewCategory)
        private val notesTextView: TextView = itemView.findViewById(R.id.textViewNotes)
        private val statusTextView: TextView = itemView.findViewById(R.id.textViewStatus)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.checkSelectTransaction)

        fun bind(transaction: Transaction) {
            merchantTextView.text = transaction.merchantName
            val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            amountTextView.text = currencyFormat.format(transaction.amount)

            val previousTransaction = if (bindingAdapterPosition > 0) {
                getItem(bindingAdapterPosition - 1)
            } else {
                null
            }
            val showGroupDate = previousTransaction == null ||
                dateKey(previousTransaction.timestamp) != dateKey(transaction.timestamp)
            groupDateTextView.visibility = if (showGroupDate) View.VISIBLE else View.GONE
            if (showGroupDate) {
                groupDateTextView.text =
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        .format(Date(transaction.timestamp))
            }

            val categoryLabel = buildString {
                if (transaction.account.isNotBlank()) append(transaction.account)
                if (transaction.category.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(transaction.category)
                    if (transaction.subcategory.isNotBlank()) append(" / ${transaction.subcategory}")
                }
                if (isEmpty()) append("Uncategorized")
            }
            categoryTextView.text = categoryLabel

            val showStatus = listFilter == ListFilter.TODAY || listFilter == ListFilter.ALL
            if (showStatus) {
                statusTextView.visibility = View.VISIBLE
                statusTextView.text = when (transaction.reviewStatus) {
                    ReviewStatus.NEEDS_REVIEW -> "Needs review"
                    ReviewStatus.REVIEWED -> "Reviewed"
                    ReviewStatus.EXPORTED -> "MM exported"
                    else -> transaction.reviewStatus
                }
            } else {
                statusTextView.visibility = View.GONE
            }

            if (!transaction.notes.isNullOrEmpty() || transaction.participants.isNotBlank()) {
                notesTextView.text = buildString {
                    if (transaction.participants.isNotBlank()) {
                        append("Split: ${transaction.participants}")
                    }
                    if (!transaction.notes.isNullOrEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(transaction.notes)
                    }
                }
                notesTextView.visibility = View.VISIBLE
            } else {
                notesTextView.visibility = View.GONE
            }

            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = selectedIds.contains(transaction.id)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (!selectionMode) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedIds.add(transaction.id)
                } else {
                    selectedIds.remove(transaction.id)
                }
                itemClickListener.onSelectionChanged(selectedIds.size)
            }
        }
    }

    private fun dateKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}

class TransactionDiffCallback : DiffUtil.ItemCallback<Transaction>() {
    override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean =
        oldItem == newItem
}

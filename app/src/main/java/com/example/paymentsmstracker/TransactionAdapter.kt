package com.example.paymentsmstracker.adapter // Or your preferred package

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.data.Transaction
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter :
    ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(TransactionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction = getItem(position)
        holder.bind(transaction)
    }

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val merchantTextView: TextView = itemView.findViewById(R.id.textViewMerchant)
        private val amountTextView: TextView = itemView.findViewById(R.id.textViewAmount)
        private val categoryTextView: TextView = itemView.findViewById(R.id.textViewCategory)
        private val dateTextView: TextView = itemView.findViewById(R.id.textViewDate)
        private val notesTextView: TextView = itemView.findViewById(R.id.textViewNotes)

        fun bind(transaction: Transaction) {

            merchantTextView.text = transaction.merchantName

            // Format amount as currency
            val currencyFormat = NumberFormat.getCurrencyInstance() // Uses default locale
            amountTextView.text = currencyFormat.format(transaction.amount)
            // You might want to color it red for debits, green for credits if you add transaction type

            categoryTextView.text = "Category: ${transaction.category}"

            // Format timestamp to a readable date
            val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
            dateTextView.text = dateFormat.format(Date(transaction.timestamp))

            if (!transaction.notes.isNullOrEmpty()) {
                notesTextView.text = "Notes: ${transaction.notes}"
                notesTextView.visibility = View.VISIBLE
            } else {
                notesTextView.visibility = View.GONE
            }
        }
    }
}

class TransactionDiffCallback : DiffUtil.ItemCallback<Transaction>() {
    override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
        return oldItem == newItem // Relies on data class `equals`
    }
}
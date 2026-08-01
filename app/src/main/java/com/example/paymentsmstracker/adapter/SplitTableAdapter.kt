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
    val people: String
)

class SplitTableAdapter(
    private val onClick: (Long) -> Unit
) : ListAdapter<SplitRowUi, SplitTableAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<SplitRowUi>() {
        override fun areItemsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SplitRowUi, newItem: SplitRowUi) = oldItem == newItem
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.textSplitDate)
        val merchant: TextView = view.findViewById(R.id.textSplitMerchant)
        val total: TextView = view.findViewById(R.id.textSplitTotal)
        val myShare: TextView = view.findViewById(R.id.textSplitMyShare)
        val friendsOwe: TextView = view.findViewById(R.id.textSplitFriendsOwe)
        val people: TextView = view.findViewById(R.id.textSplitPeople)
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
        holder.itemView.setOnClickListener { onClick(row.id) }
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
            return SplitRowUi(
                id = tx.id,
                date = date,
                merchant = tx.merchantName,
                total = total.toPlainString(),
                myShare = myShare.toPlainString(),
                friendsOwe = friends.toPlainString(),
                people = peopleLabel
            )
        }
    }
}

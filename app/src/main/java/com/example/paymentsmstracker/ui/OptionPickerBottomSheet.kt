package com.example.paymentsmstracker.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

object OptionPickerBottomSheet {

    fun show(
        context: Context,
        title: String,
        options: List<String>,
        selected: String?,
        spanCount: Int = 2,
        onSelected: (String) -> Unit
    ) {
        if (options.isEmpty()) return
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_option_picker, null)
        view.findViewById<TextView>(R.id.textPickerTitle).text = title
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPickerOptions)
        recycler.layoutManager = GridLayoutManager(context, spanCount.coerceAtLeast(1))
        recycler.adapter = OptionGridAdapter(options, selected) { value ->
            onSelected(value)
            dialog.dismiss()
        }
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private class OptionGridAdapter(
        private val options: List<String>,
        private val selected: String?,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<OptionGridAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val button: MaterialButton = view.findViewById(R.id.buttonOption)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_option_grid, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val value = options[position]
            holder.button.text = value
            val isSelected = value.equals(selected, ignoreCase = true)
            if (isSelected) {
                holder.button.setStrokeWidth(3)
            } else {
                holder.button.setStrokeWidth(1)
            }
            holder.button.setOnClickListener { onClick(value) }
        }

        override fun getItemCount(): Int = options.size
    }
}

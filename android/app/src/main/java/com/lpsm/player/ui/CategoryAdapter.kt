package com.lpsm.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.player.R

data class CategoryRow(val name: String, val count: Int)

class CategoryAdapter(private val select: (String) -> Unit) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
    private var rows = emptyList<CategoryRow>()
    private var selected = "Tudo"

    fun submit(items: List<CategoryRow>, selectedName: String) {
        if (rows == items && selected == selectedName) return
        rows = items
        selected = selectedName
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
    )

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        holder.count.text = row.count.toString()
        holder.itemView.isSelected = row.name == selected
        holder.itemView.setOnClickListener { select(row.name) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.categoryName)
        val count: TextView = view.findViewById(R.id.categoryCount)
    }
}

package com.tecnoselva.factura.features.pos.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tecnoselva.factura.databinding.ItemPosCartBinding
import com.tecnoselva.factura.features.pos.model.PosCartItem
import java.util.Locale

class PosCartAdapter(
    private val onDelta: (PosCartItem, Int) -> Unit,
    private val onDelete: (PosCartItem) -> Unit
) : RecyclerView.Adapter<PosCartAdapter.Holder>() {
    private val items = mutableListOf<PosCartItem>()
    class Holder(val binding: ItemPosCartBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemPosCartBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.tvCartName.text = item.name + (item.variantLabel?.let { " · $it" } ?: "")
        holder.binding.tvCartPrice.text = "S/ ${money(item.unitPrice)} c/u · S/ ${money(item.subtotal)}"
        holder.binding.tvCartQuantity.text = item.quantity.toString()
        holder.binding.btnCartMinus.setOnClickListener { onDelta(item, -1) }
        holder.binding.btnCartPlus.setOnClickListener { onDelta(item, 1) }
        holder.binding.btnCartDelete.setOnClickListener { onDelete(item) }
    }
    fun submitList(next: List<PosCartItem>) { items.clear(); items.addAll(next); notifyDataSetChanged() }
    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
}

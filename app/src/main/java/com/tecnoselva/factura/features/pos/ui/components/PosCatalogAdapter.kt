package com.tecnoselva.factura.features.pos.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tecnoselva.factura.R
import com.tecnoselva.factura.databinding.ItemPosCatalogBinding
import com.tecnoselva.factura.features.pos.model.PosCatalogItem
import com.tecnoselva.factura.features.pos.model.PosCatalogType
import java.util.Locale

class PosCatalogAdapter(private val onClick: (PosCatalogItem) -> Unit) : RecyclerView.Adapter<PosCatalogAdapter.Holder>() {
    private val items = mutableListOf<PosCatalogItem>()
    class Holder(val binding: ItemPosCatalogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemPosCatalogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.tvCatalogName.text = item.name
        holder.binding.tvCatalogMeta.text = listOf(item.category, item.sku.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
        holder.binding.tvCatalogPrice.text = if (item.hasVariants) "Desde S/ ${money(item.price)}" else "S/ ${money(item.price)}"
        holder.binding.ivCatalogType.setImageResource(if (item.type == PosCatalogType.PRODUCT) R.drawable.ic_store else R.drawable.ic_document)
        holder.binding.tvCatalogStock.text = when {
            item.type == PosCatalogType.SERVICE -> "SERVICIO"
            !item.trackInventory -> "STOCK LIBRE"
            item.available -> "${item.stock?.toInt() ?: 0} DISPONIBLES"
            else -> "SIN STOCK"
        }
        holder.binding.tvCatalogStock.setTextColor(ContextCompat.getColor(context, if (item.available) R.color.brand_success else R.color.brand_danger))
        holder.binding.root.alpha = if (item.available) 1f else 0.58f
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    fun submitList(next: List<PosCatalogItem>) {
        items.clear(); items.addAll(next); notifyDataSetChanged()
    }
    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
}

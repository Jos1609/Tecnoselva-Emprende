package com.tecnoselva.factura.features.billing.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tecnoselva.factura.databinding.ItemInvoiceRowBinding
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import java.util.Locale

class InvoiceLinesAdapter(
    private val items: MutableList<InvoiceLineItem>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<InvoiceLinesAdapter.LineViewHolder>() {

    class LineViewHolder(val binding: ItemInvoiceRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        return LineViewHolder(ItemInvoiceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val item = items[position]
        val typeLabel = if (item.itemType == "bien") "Producto · NIU" else "Servicio · ZZ"
        val taxLabel = if (item.taxed) "Afecto IGV" else "Exonerado"
        holder.binding.tvRowDescription.text = item.description
        holder.binding.tvRowMeta.text = "$typeLabel  •  ${formatNumber(item.quantity)} × S/ ${formatMoney(item.unitPrice)}  •  $taxLabel"
        holder.binding.tvRowTotal.text = "S/ ${formatMoney(item.quantity * item.unitPrice)}"
        holder.binding.btnEditRow.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onEdit)
        }
        holder.binding.btnDeleteRow.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onDelete)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatMoney(value: Double) = String.format(Locale.US, "%.2f", value)

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)
    }
}

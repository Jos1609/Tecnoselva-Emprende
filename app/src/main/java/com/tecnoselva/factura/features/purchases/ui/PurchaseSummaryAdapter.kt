package com.tecnoselva.factura.features.purchases.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tecnoselva.factura.databinding.ItemPurchaseSummaryBinding
import com.tecnoselva.factura.features.purchases.model.Purchase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PurchaseSummaryAdapter(
    private val onAction: (Purchase, String) -> Unit,
    private val onOpen: (Purchase) -> Unit
) : RecyclerView.Adapter<PurchaseSummaryAdapter.Holder>() {
    private var items: List<Purchase> = emptyList()
    fun submitList(value: List<Purchase>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemPurchaseSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
    inner class Holder(private val binding: ItemPurchaseSummaryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Purchase) = with(binding) {
            root.setOnClickListener { onOpen(item) }
            tvPurchaseSupplier.text = item.supplierName
            tvPurchaseStatus.text = statusLabel(item.status)
            tvPurchaseDocument.text = item.invoiceNumber.ifBlank { "Compra sin documento" }
            tvPurchaseDate.text = formatDate(item.purchaseDate)
            tvPurchaseTotal.text = "S/ ${String.format(Locale.US, "%.2f", item.total)}"
            purchaseActions.removeAllViews()
            if (item.status == "pending") {
                purchaseActions.addView(MaterialButton(root.context).apply { text = "Recibir"; isAllCaps = false; setOnClickListener { onAction(item, "receive") } })
                purchaseActions.addView(MaterialButton(root.context).apply { text = "Cancelar"; isAllCaps = false; setOnClickListener { onAction(item, "cancel") } })
            }
        }
    }
    private fun statusLabel(value: String) = when (value) { "received" -> "RECIBIDA"; "cancelled" -> "CANCELADA"; else -> "PENDIENTE" }
    private fun formatDate(value: String): String = runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) ?: Date()) }.getOrDefault(value)
}

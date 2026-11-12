package com.tecnoselva.factura.features.orders.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tecnoselva.factura.databinding.ItemOrderSummaryBinding
import com.tecnoselva.factura.features.orders.model.OrderSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderSummaryAdapter(private val onClick: (OrderSummary) -> Unit) : RecyclerView.Adapter<OrderSummaryAdapter.Holder>() {
    private var items: List<OrderSummary> = emptyList()

    fun submitList(value: List<OrderSummary>) { items = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemOrderSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemOrderSummaryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: OrderSummary) = with(binding) {
            tvOrderNumber.text = order.orderNumber
            tvOrderStatus.text = statusLabel(order.status)
            tvOrderCustomer.text = "${order.customerName} · ${documentLabel(order.documentType)}"
            tvOrderItems.text = order.itemsSummary.ifBlank { "Toca para ver los productos del pedido" }
            tvOrderDate.text = formatDate(order.createdAt)
            tvOrderTotal.text = "S/ ${String.format(Locale.US, "%.2f", order.total)}"
            root.setOnClickListener { onClick(order) }
        }
    }

    private fun statusLabel(value: String) = when (value) {
        "pending" -> "PENDIENTE"
        "confirmed" -> "CONFIRMADO"
        "preparing" -> "PREPARANDO"
        "ready" -> "LISTO"
        "shipped" -> "ENVIADO"
        "cancelled" -> "ANULADO"
        else -> "COMPLETADO"
    }

    private fun documentLabel(value: String) = when (value) {
        "factura" -> "Factura"
        "boleta" -> "Boleta"
        else -> "Nota de venta"
    }

    private fun formatDate(value: String): String = runCatching {
        val date = value.toLongOrNull()?.let(::Date) ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(value)
        date?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(it) } ?: value
    }.getOrDefault(value)
}

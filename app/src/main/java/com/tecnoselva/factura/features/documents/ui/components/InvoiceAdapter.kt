package com.tecnoselva.factura.features.documents.ui.components

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.tecnoselva.factura.R
import com.tecnoselva.factura.features.documents.model.Invoice
import com.tecnoselva.factura.databinding.ItemInvoiceBinding
import java.text.SimpleDateFormat
import java.util.Locale

class InvoiceAdapter(
    private val onShareClick: (Invoice) -> Unit = {},
    private val onInvoiceClick: (Invoice) -> Unit = {}
) : RecyclerView.Adapter<InvoiceAdapter.InvoiceViewHolder>() {
    private val invoices = mutableListOf<Invoice>()

    class InvoiceViewHolder(val binding: ItemInvoiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder =
        InvoiceViewHolder(ItemInvoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {
        val invoice = invoices[position]
        val context = holder.binding.root.context
        val docLabel = when (invoice.documentType) {
            "01" -> "FACTURA"
            "03" -> "BOLETA"
            "07" -> "NOTA DE CRÉDITO"
            "08" -> "NOTA DE DÉBITO"
            else -> "COMPROBANTE ${invoice.documentType}"
        }
        val currencySymbol = if (invoice.currency.equals("PEN", true)) "S/" else invoice.currency
        holder.binding.tvInvoiceId.text = "$docLabel  ·  ${invoice.id}"
        holder.binding.tvClientName.text = "${invoice.clientName} · ${invoice.clientRuc}"
        holder.binding.tvInvoiceTotal.text = "$currencySymbol ${money(invoice.totalAmount)}"
        holder.binding.tvInvoiceMeta.text = "Subtotal $currencySymbol ${money(invoice.operationSubtotal)}  ·  IGV $currencySymbol ${money(invoice.igvAmount)}"
        holder.binding.tvInvoiceDate.text = formatDate(invoice.date)
        holder.binding.tvInvoiceStatus.text = statusLabel(invoice.status)
        val (statusColor, statusBackground) = when (invoice.status.lowercase(Locale.ROOT)) {
            "accepted", "accepted_with_observations" -> R.color.brand_success to R.color.brand_success_soft
            "observed", "rejected", "error" -> R.color.brand_danger to R.color.brand_danger_soft
            "sent", "pending", "generated", "signed", "processing" -> R.color.brand_warning to R.color.brand_warning_soft
            else -> R.color.brand_text_secondary to R.color.brand_surface_subtle
        }
        holder.binding.tvInvoiceStatus.setTextColor(ContextCompat.getColor(context, statusColor))
        holder.binding.tvInvoiceStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, statusBackground))
        holder.binding.tvInvoiceError.visibility = if (invoice.errorMessage.isBlank()) View.GONE else View.VISIBLE
        holder.binding.tvInvoiceError.text = invoice.errorMessage
        holder.binding.root.setOnClickListener { onInvoiceClick(invoice) }
        holder.binding.btnShareInvoice.setOnClickListener { onShareClick(invoice) }
    }

    fun submitList(items: List<Invoice>) {
        invoices.clear()
        invoices.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = invoices.size

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun statusLabel(status: String): String = when (status.lowercase(Locale.ROOT)) {
        "accepted" -> "ACEPTADO POR SUNAT"
        "accepted_with_observations" -> "ACEPTADO CON OBSERVACIONES"
        "observed" -> "OBSERVADO"
        "rejected" -> "RECHAZADO"
        "error" -> "ERROR DE ENVÍO"
        "sent" -> "ENVIADO A SUNAT"
        "pending" -> "PENDIENTE"
        "generated" -> "GENERADO"
        "signed" -> "FIRMADO"
        "processing" -> "PROCESANDO"
        else -> status.replace('_', ' ').uppercase(Locale.getDefault())
    }

    private fun formatDate(raw: String): String {
        if (raw.isBlank()) return "Fecha no disponible"
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { isLenient = true }
            val date = input.parse(raw.substringBefore('.').removeSuffix("Z")) ?: return raw
            SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale("es", "PE")).format(date)
        } catch (_: Exception) {
            raw.replace('T', ' ').take(16)
        }
    }
}

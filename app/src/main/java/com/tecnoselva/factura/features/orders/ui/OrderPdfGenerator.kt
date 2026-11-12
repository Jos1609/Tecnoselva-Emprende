package com.tecnoselva.factura.features.orders.ui

import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.documents.model.Invoice
import com.tecnoselva.factura.features.documents.model.InvoiceDetailLine
import com.tecnoselva.factura.features.documents.ui.InvoicePdfGenerator
import com.tecnoselva.factura.features.orders.model.OrderSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reuses the exact same A4 template used by Facturación. */
object OrderPdfGenerator {
    fun create(directory: File, session: SessionManager, order: OrderSummary): File {
        val normalizedStatus = order.sunatStatus.lowercase(Locale.US)
        val acceptedSunatStatuses = setOf("accepted", "accepted_with_observations", "observed")
        val normalizedDocumentType = order.documentType.lowercase(Locale.US)
        val hasElectronicType = normalizedDocumentType in setOf("factura", "boleta", "01", "03")
        val hasLinkedElectronicDocument = hasElectronicType && (
            order.hasElectronicDocument ||
                (order.documentNumber.isNotBlank() && order.sunatStatus.isNotBlank())
            )
        val isAcceptedElectronicDocument = hasLinkedElectronicDocument &&
            normalizedStatus in acceptedSunatStatuses &&
            order.sunatQrData.isNotBlank()
        val pdfDocumentType = if (hasLinkedElectronicDocument) {
            when (normalizedDocumentType) {
                "factura", "01" -> "01"
                else -> "03"
            }
        } else {
            "NV"
        }
        val pdfNumber = if (hasLinkedElectronicDocument) order.documentNumber else order.orderNumber
        val invoice = Invoice(
            id = pdfNumber.ifBlank { order.orderNumber },
            clientName = order.customerName,
            clientRuc = order.customerDocumentNumber,
            description = order.items.firstOrNull()?.name ?: "Pedido",
            quantity = order.items.firstOrNull()?.quantity?.toInt() ?: 1,
            unitPrice = order.items.firstOrNull()?.unitPrice ?: order.total,
            totalAmount = order.total,
            date = normalizeDate(order.createdAt),
            status = order.status,
            documentType = pdfDocumentType,
            subtotal = order.subtotal.takeIf { it > 0.0 } ?: order.total,
            igvAmount = order.tax,
            customerAddress = order.customerAddress,
            serie = order.documentNumber.substringBefore('-', ""),
            correlative = order.documentNumber.substringAfter('-', ""),
            currency = "PEN",
            lineItems = order.items.map { item ->
                InvoiceDetailLine(
                    description = item.name + item.variantLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    itemType = if (item.itemType == "service") "servicio" else "bien",
                    taxed = order.taxRate > 0.0
                )
            },
            notes = listOf(
                "Pedido: ${order.orderNumber}",
                "Estado: ${statusLabel(order.status)}",
                "Pago: ${paymentLabel(order.paymentStatus)} · Pagado S/ ${money(order.amountPaid)} · Pendiente S/ ${money(order.amountPending)}"
            ).joinToString(" · "),
            taxRate = order.taxRate,
            qrData = if (isAcceptedElectronicDocument) order.sunatQrData else "",
            hash = if (isAcceptedElectronicDocument) order.sunatHash else "",
            allowanceTotalAmount = order.discount
        )
        return InvoicePdfGenerator.create(
            documentsDirectory = directory,
            businessName = session.businessName,
            issuerName = session.issuerLegalName,
            issuerRuc = session.issuerRuc,
            issuerAddress = session.issuerAddress,
            issuerCity = session.issuerCity,
            issuerLogoUrl = session.issuerLogoUrl,
            issuerSlogan = session.issuerSlogan,
            issuerPhone = session.issuerPhone,
            invoice = invoice
        )
    }

    private fun normalizeDate(value: String): String = value.toLongOrNull()?.let {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it))
    } ?: value

    private fun money(value: Double) = "%.2f".format(Locale.US, value)
    private fun statusLabel(value: String) = when (value) {
        "pending" -> "Pendiente"
        "confirmed" -> "Confirmado"
        "preparing" -> "Preparando"
        "ready" -> "Listo"
        "shipped" -> "Enviado"
        "cancelled" -> "Cancelado"
        else -> "Entregado"
    }
    private fun paymentLabel(value: String) = when (value) {
        "paid" -> "Pagado"
        "partial" -> "Pago parcial"
        "refunded" -> "Reembolsado"
        else -> "Pendiente"
    }
}

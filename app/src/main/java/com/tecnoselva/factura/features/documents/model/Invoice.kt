package com.tecnoselva.factura.features.documents.model

data class Invoice(
    val id: String,
    val clientName: String,
    val clientRuc: String,
    val description: String,
    val quantity: Int,
    val unitPrice: Double,
    val totalAmount: Double,
    val date: String,
    val status: String = "pending",
    val documentType: String = "03",
    val documentUuid: String = "",
    val subtotal: Double = 0.0,
    val igvAmount: Double = 0.0,
    val customerAddress: String = "",
    val serie: String = "",
    val correlative: String = "",
    val currency: String = "PEN",
    val errorMessage: String = "",
    val lineItems: List<InvoiceDetailLine> = emptyList(),
    val hash: String = "",
    val qrData: String = "",
    val notes: String = "",
    val taxRate: Double = 18.0,
    val allowanceTotalAmount: Double = 0.0,
    val chargeTotalAmount: Double = 0.0,
    val payableAmount: Double = totalAmount,
    val legends: List<InvoiceLegend> = emptyList()
) {
    /** Recover the operation base when older database rows have subtotal=0. */
    val operationSubtotal: Double
        get() = subtotal.takeIf { it > 0.0 }
            ?: lineItems.sumOf { line -> line.quantity * if (line.taxed) line.unitPrice / 1.18 else line.unitPrice }
                .takeIf { it > 0.0 }
            ?: (payableAmount - chargeTotalAmount + allowanceTotalAmount - if (taxRate > 0.0) igvAmount else 0.0)
                .coerceAtLeast(0.0)
}

data class InvoiceDetailLine(
    val description: String,
    val quantity: Double,
    /** Unit price as stored in the SUNAT line payload; this includes IGV for taxed items. */
    val unitPrice: Double,
    val itemType: String = "bien",
    val taxed: Boolean = true
)

data class InvoiceLegend(val code: String, val value: String)

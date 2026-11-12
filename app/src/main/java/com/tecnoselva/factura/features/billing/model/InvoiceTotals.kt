package com.tecnoselva.factura.features.billing.model

data class InvoiceTotals(
    val taxableSubtotal: Double,
    val exemptSubtotal: Double,
    val igv: Double,
    val total: Double
)

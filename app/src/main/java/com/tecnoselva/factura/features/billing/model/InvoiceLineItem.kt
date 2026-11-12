package com.tecnoselva.factura.features.billing.model

data class InvoiceLineItem(
    var description: String = "",
    var quantity: Double = 1.0,
    var unitPrice: Double = 0.0,
    var itemType: String = "bien", // "bien" = NIU, "servicio" = ZZ
    var taxed: Boolean = true // true = afecto IGV, false = exonerado
)

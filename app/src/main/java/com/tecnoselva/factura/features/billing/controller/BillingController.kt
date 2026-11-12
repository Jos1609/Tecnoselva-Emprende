package com.tecnoselva.factura.features.billing.controller

import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.billing.model.InvoiceTotals

/** Billing calculations and line validation, kept out of the Android view layer. */
class BillingController(private val igvRate: Double = 0.18) {
    fun calculateTotals(items: List<InvoiceLineItem>): InvoiceTotals {
        val taxable = items.filter { it.taxed }.sumOf { safe(it.quantity) * safe(it.unitPrice) }
        val exempt = items.filterNot { it.taxed }.sumOf { safe(it.quantity) * safe(it.unitPrice) }
        val igv = taxable * igvRate
        return InvoiceTotals(taxable, exempt, igv, taxable + exempt + igv)
    }

    fun validItems(items: List<InvoiceLineItem>): List<InvoiceLineItem> = items.filter {
        it.description.isNotBlank() && it.quantity > 0 && it.unitPrice > 0
    }

    private fun safe(value: Double): Double = value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}

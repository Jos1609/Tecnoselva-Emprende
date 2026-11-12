package com.tecnoselva.factura.features.billing.data

import com.google.gson.JsonObject
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.auth.model.CustomerLookupResult
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.billing.model.OperationResult

/** Billing feature operations backed by the shared Supabase gateway. */
class BillingService {
    fun lookupCustomer(storeId: String, documentType: String, documentNumber: String): CustomerLookupResult =
        SupabaseService.lookupCustomer(storeId, documentType, documentNumber)

    fun issueDocument(
        storeId: String,
        documentType: String,
        customerDocType: String,
        customerDocNumber: String,
        customerName: String,
        customerAddress: String,
        items: List<InvoiceLineItem>,
        reference: JsonObject?,
        observations: String?,
        orderId: String? = null
    ): OperationResult = SupabaseService.createInvoice(
        storeId = storeId,
        documentType = documentType,
        customerDocType = customerDocType,
        clientRuc = customerDocNumber,
        clientName = customerName,
        clientAddress = customerAddress,
        lineItems = items,
        reference = reference,
        observations = observations,
        orderId = orderId
    )
}

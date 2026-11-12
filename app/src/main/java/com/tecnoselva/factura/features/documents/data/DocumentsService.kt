package com.tecnoselva.factura.features.documents.data

import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.documents.model.Invoice

/** Document listing operations backed by the shared Supabase gateway. */
class DocumentsService {
    fun fetchForStore(storeId: String, search: String?): List<Invoice> =
        SupabaseService.fetchInvoices(storeId, search)

    fun fetchByOrderId(storeId: String, orderId: String): Invoice? =
        SupabaseService.fetchInvoiceByOrderId(storeId, orderId)

    fun fetchIssuerDetails(storeId: String): Map<String, String> =
        SupabaseService.fetchIssuerDetails(storeId)
}

package com.tecnoselva.factura.features.billing.data

import android.util.Log
import com.google.gson.JsonObject
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.auth.model.CustomerLookupResult
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.billing.model.OperationResult

class BillingRepository(private val sessionRepository: SessionRepository) {
    private val billingService = BillingService()

    fun lookupCustomer(docType: String, docNumber: String, onResult: (CustomerLookupResult) -> Unit) {
        val storeId = sessionRepository.storeId
        Thread {
            val result = try {
                sessionRepository.withValidSession {
                    billingService.lookupCustomer(storeId, docType, docNumber)
                }
            } catch (error: Exception) {
                CustomerLookupResult(false, null, null, error.localizedMessage ?: "No se pudo validar la sesión")
            }
            onResult(result)
        }.start()
    }

    fun saveInvoiceComprobante(
        documentType: String,
        customerDocType: String,
        clientRuc: String,
        clientName: String,
        clientAddress: String,
        lineItems: List<InvoiceLineItem>,
        reference: JsonObject? = null,
        observations: String? = null,
        orderId: String? = null,
        onResult: (OperationResult) -> Unit
    ) {
        val storeId = sessionRepository.storeId
        Thread {
            val result = try {
                sessionRepository.withValidSession {
                    billingService.issueDocument(
                        storeId = storeId,
                        documentType = documentType,
                        customerDocType = customerDocType,
                        customerDocNumber = clientRuc,
                        customerName = clientName,
                        customerAddress = clientAddress,
                        items = lineItems,
                        reference = reference,
                        observations = observations,
                        orderId = orderId
                    )
                }
            } catch (e: Exception) {
                Log.e("BillingFlow", "Excepción en BillingRepository.saveInvoiceComprobante", e)
                OperationResult(false, "Error inesperado: ${e.localizedMessage}")
            }
            onResult(result)
        }.start()
    }
}

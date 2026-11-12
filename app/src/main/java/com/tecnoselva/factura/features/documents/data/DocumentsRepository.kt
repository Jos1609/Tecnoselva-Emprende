package com.tecnoselva.factura.features.documents.data

import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.documents.model.Invoice

/** Read-side gateway for the documents feature. */
class DocumentsRepository(
    private val sessionRepository: SessionRepository,
    private val documentsService: DocumentsService = DocumentsService()
) {
    fun getDocuments(search: String?): List<Invoice> {
        val storeId = sessionRepository.storeId
        require(storeId.isNotBlank()) { "No hay una tienda asociada a la sesión; vuelve a iniciar sesión." }
        return sessionRepository.withValidSession {
            documentsService.fetchForStore(storeId, search)
        }
    }

    fun getDocumentByOrderId(orderId: String): Invoice? {
        val storeId = sessionRepository.storeId
        require(storeId.isNotBlank()) { "No hay una tienda asociada a la sesión." }
        return sessionRepository.withValidSession {
            documentsService.fetchByOrderId(storeId, orderId)
        }
    }

    fun getReferenceDocuments(): List<Invoice> = getDocuments(null).filter {
        (it.documentType == "01" || it.documentType == "03") &&
            (it.status == "accepted" || it.status == "accepted_with_observations" || it.status == "observed")
    }

    fun getIssuerDetails(): Map<String, String> {
        val storeId = sessionRepository.storeId
        require(storeId.isNotBlank()) { "No hay una tienda asociada a la sesión." }
        return sessionRepository.withValidSession {
            documentsService.fetchIssuerDetails(storeId)
        }
    }
}

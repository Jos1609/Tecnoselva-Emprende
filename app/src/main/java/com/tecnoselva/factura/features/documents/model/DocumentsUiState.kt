package com.tecnoselva.factura.features.documents.model

data class DocumentsUiState(
    val allDocuments: List<Invoice> = emptyList(),
    val visibleDocuments: List<Invoice> = emptyList(),
    val query: String = "",
    val filter: DocumentFilter = DocumentFilter.ALL,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val sessionExpired: Boolean = false,
    val hasLoaded: Boolean = false
)

enum class DocumentFilter {
    ALL, INVOICES, RECEIPTS, NOTES
}

package com.tecnoselva.factura.features.documents.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tecnoselva.factura.features.auth.data.SessionExpiredException
import com.tecnoselva.factura.features.documents.data.DocumentsRepository
import com.tecnoselva.factura.features.documents.model.DocumentFilter
import com.tecnoselva.factura.features.documents.model.DocumentsUiState
import com.tecnoselva.factura.features.documents.model.Invoice
import java.util.concurrent.Executors

/** Owns document loading, query debounce and client-side type filters. */
class DocumentsController(
    private val repository: DocumentsRepository,
    private val onStateChanged: (DocumentsUiState) -> Unit,
    initialDocuments: List<Invoice> = emptyList(),
    private val onDocumentsLoaded: (List<Invoice>) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = DocumentsUiState(
        allDocuments = initialDocuments,
        visibleDocuments = initialDocuments,
        hasLoaded = initialDocuments.isNotEmpty()
    )
    private var requestVersion = 0
    private var queryRunnable: Runnable? = null

    fun state(): DocumentsUiState = state

    fun load() {
        val version = ++requestVersion
        val search = state.query.trim().takeIf { it.isNotBlank() }
        publish(state.copy(isLoading = true, errorMessage = null, sessionExpired = false))
        executor.execute {
            try {
                val documents = repository.getDocuments(search)
                mainHandler.post {
                    if (version != requestVersion) return@post
                    if (search == null) onDocumentsLoaded(documents)
                    publish(state.copy(
                        allDocuments = documents,
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = null,
                        sessionExpired = false
                    ))
                    applyFilters()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not load billing documents", error)
                mainHandler.post {
                    if (version != requestVersion) return@post
                    publish(state.copy(
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = error.localizedMessage ?: "Error de conexión",
                        sessionExpired = error is SessionExpiredException
                    ))
                }
            }
        }
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
        publish(state)
        queryRunnable?.let(mainHandler::removeCallbacks)
        queryRunnable = Runnable { load() }.also { mainHandler.postDelayed(it, SEARCH_DEBOUNCE_MS) }
    }

    fun setFilter(filter: DocumentFilter) {
        if (state.filter == filter) return
        state = state.copy(filter = filter)
        applyFilters()
    }

    fun clearError() = publish(state.copy(errorMessage = null))

    fun dispose() {
        requestVersion++
        queryRunnable?.let(mainHandler::removeCallbacks)
        executor.shutdownNow()
    }

    private fun applyFilters() {
        val query = state.query.trim()
        val visible = state.allDocuments.filter { document ->
            val typeMatches = when (state.filter) {
                DocumentFilter.ALL -> true
                DocumentFilter.INVOICES -> document.documentType == "01"
                DocumentFilter.RECEIPTS -> document.documentType == "03"
                DocumentFilter.NOTES -> document.documentType == "07" || document.documentType == "08"
            }
            val alreadySearchedOnServer = query.isNotBlank() && state.hasLoaded
            typeMatches && (query.isBlank() || alreadySearchedOnServer || document.matches(query))
        }
        publish(state.copy(visibleDocuments = visible))
    }

    private fun publish(next: DocumentsUiState) {
        state = next
        onStateChanged(next)
    }

    private fun com.tecnoselva.factura.features.documents.model.Invoice.matches(query: String): Boolean =
        id.contains(query, ignoreCase = true) || clientName.contains(query, ignoreCase = true) || clientRuc.contains(query, ignoreCase = true)

    private companion object {
        const val TAG = "BillingFlow"
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

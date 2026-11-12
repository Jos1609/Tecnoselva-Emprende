package com.tecnoselva.factura.features.documents.ui

import android.os.Bundle
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.databinding.ActivityDocumentsBinding
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.auth.ui.LoginActivity
import com.tecnoselva.factura.features.documents.controller.DocumentsController
import com.tecnoselva.factura.features.documents.data.DocumentsRepository
import com.tecnoselva.factura.features.documents.data.DocumentsLocalCache
import com.tecnoselva.factura.features.documents.model.DocumentFilter
import com.tecnoselva.factura.features.documents.model.DocumentsUiState
import com.tecnoselva.factura.features.documents.ui.components.InvoiceAdapter
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity
import com.tecnoselva.factura.features.billing.ui.CreateInvoiceActivity
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import androidx.core.content.FileProvider
import java.util.concurrent.Executors

class DocumentsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var adapter: InvoiceAdapter
    private lateinit var controller: DocumentsController
    private lateinit var documentsRepository: DocumentsRepository
    private lateinit var localCache: DocumentsLocalCache
    private val shareExecutor = Executors.newSingleThreadExecutor()
    private var redirectingToLogin = false
    private var refreshAfterCreatingDocument = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionManager = SessionManager(this)
        if (!sessionManager.isLoggedIn) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.BILLING)
        binding.bottomNavigationDocuments.selectedItemId = R.id.nav_billing
        binding.bottomNavigationDocuments.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { openTopLevel(HomeActivity::class.java); true }
                R.id.nav_sales -> { openTopLevel(PosActivity::class.java); true }
                R.id.nav_inventory -> { openTopLevel(LocalCatalogActivity::class.java); true }
                R.id.nav_billing -> true
                else -> false
            }
        }

        adapter = InvoiceAdapter(::shareInvoicePdf, ::previewInvoice)
        binding.rvInvoices.layoutManager = LinearLayoutManager(this)
        binding.rvInvoices.adapter = adapter
        binding.btnNewDocument.setOnClickListener {
            refreshAfterCreatingDocument = true
            startActivity(Intent(this, CreateInvoiceActivity::class.java))
            applyNavigationTransition()
        }
        documentsRepository = DocumentsRepository(SessionRepository(sessionManager))
        localCache = DocumentsLocalCache(this)
        val cachedDocuments = localCache.load()
        controller = DocumentsController(documentsRepository, ::render, cachedDocuments, localCache::save)

        binding.groupDocumentFilter.check(R.id.filterAll)
        binding.groupDocumentFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) controller.setFilter(checkedId.toDocumentFilter())
        }
        binding.etInvoiceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                controller.setQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.tvInvoicesMessage.setOnClickListener {
            if (controller.state().errorMessage != null) controller.load()
        }
        binding.swipeRefreshInvoices.setOnRefreshListener { controller.load() }
        render(controller.state())
        if (cachedDocuments.isEmpty()) controller.load()
    }

    override fun onResume() {
        super.onResume()
        if (refreshAfterCreatingDocument && ::controller.isInitialized) {
            refreshAfterCreatingDocument = false
            controller.load()
        }
    }

    private fun openTopLevel(destination: Class<out AppCompatActivity>) {
        startActivity(Intent(this, destination).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        applyNavigationTransition()
        finish()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.dispose()
        shareExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun render(state: DocumentsUiState) {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        if (state.sessionExpired) {
            redirectToLogin()
            return
        }
        binding.progressInvoices.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.swipeRefreshInvoices.isRefreshing = false
        if (state.errorMessage != null) {
            binding.tvInvoicesMessage.text = "No se pudieron cargar los documentos. Toca aquí para reintentar.\n${state.errorMessage}"
            binding.tvInvoicesMessage.visibility = View.VISIBLE
            binding.tvInvoicesMessage.isClickable = true
            Snackbar.make(binding.root, state.errorMessage, Snackbar.LENGTH_LONG).show()
            return
        }

        adapter.submitList(state.visibleDocuments)
        binding.tvInvoiceCount.text = when {
            !state.hasLoaded -> "Cargando documentos…"
            state.allDocuments.isEmpty() && state.query.isBlank() -> "Comprobantes de hoy · Busca para consultar el historial"
            state.allDocuments.isEmpty() -> "Búsqueda en el historial"
            else -> "${state.visibleDocuments.size} de ${state.allDocuments.size} documentos · ${if (state.query.isBlank()) "Hoy · Desliza para actualizar" else "Historial"}"
        }

        val emptyMessage = when {
            !state.hasLoaded -> null
            state.visibleDocuments.isNotEmpty() -> null
            state.allDocuments.isEmpty() && state.query.isBlank() -> "No hay comprobantes emitidos hoy. Busca un número o cliente para consultar el historial."
            state.allDocuments.isEmpty() -> "No hay documentos que coincidan con la búsqueda."
            else -> "No hay documentos que coincidan con este filtro."
        }
        binding.tvInvoicesMessage.text = emptyMessage.orEmpty()
        binding.tvInvoicesMessage.visibility = if (emptyMessage == null) View.GONE else View.VISIBLE
        binding.tvInvoicesMessage.isClickable = false
    }

    private fun redirectToLogin() {
        if (redirectingToLogin) return
        redirectingToLogin = true
        SessionManager(this).logout()
        Toast.makeText(this, "Tu sesión venció. Ingresa nuevamente para continuar.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun Int.toDocumentFilter(): DocumentFilter = when (this) {
        R.id.filterInvoices -> DocumentFilter.INVOICES
        R.id.filterReceipts -> DocumentFilter.RECEIPTS
        R.id.filterNotes -> DocumentFilter.NOTES
        else -> DocumentFilter.ALL
    }

    private fun shareInvoicePdf(invoice: com.tecnoselva.factura.features.documents.model.Invoice) {
        withPdf(invoice) { pdfFile ->
            try {
                val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdfFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TITLE, pdfFile.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir comprobante PDF A4"))
            } catch (error: Exception) {
                Snackbar.make(binding.root, "No se pudo compartir el PDF: ${error.localizedMessage ?: "error desconocido"}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun previewInvoice(invoice: com.tecnoselva.factura.features.documents.model.Invoice) {
        withPdf(invoice) { pdfFile ->
            startActivity(Intent(this, DocumentPreviewActivity::class.java).apply {
                putExtra(DocumentPreviewActivity.EXTRA_PDF_PATH, pdfFile.absolutePath)
                putExtra(DocumentPreviewActivity.EXTRA_TITLE, "${invoice.id} · ${invoice.clientName}")
            })
        }
    }

    private fun withPdf(
        invoice: com.tecnoselva.factura.features.documents.model.Invoice,
        onReady: (java.io.File) -> Unit
    ) {
        val documentsDirectory = java.io.File(filesDir, "documents")
        val cachedPdf = InvoicePdfGenerator.cachedFile(documentsDirectory, invoice)
        if (cachedPdf != null) {
            onReady(cachedPdf)
            return
        }

        Snackbar.make(binding.root, "Preparando comprobante A4…", Snackbar.LENGTH_SHORT).show()
        shareExecutor.execute {
            try {
                val session = SessionManager(this)
                val issuer = documentsRepository.getIssuerDetails()
                session.issuerRuc = issuer["ruc"].orEmpty().ifBlank { session.issuerRuc }
                session.issuerLegalName = issuer["legalName"].orEmpty().ifBlank { session.issuerLegalName }
                session.issuerAddress = issuer["address"].orEmpty().ifBlank { session.issuerAddress }
                session.issuerCity = issuer["city"].orEmpty().ifBlank { session.issuerCity }
                session.issuerLogoUrl = issuer["logoUrl"].orEmpty().ifBlank { session.issuerLogoUrl }
                session.issuerSlogan = issuer["slogan"].orEmpty().ifBlank { session.issuerSlogan }
                session.issuerPhone = issuer["phone"].orEmpty().ifBlank { session.issuerPhone }
                val pdfFile = InvoicePdfGenerator.create(
                    documentsDirectory = documentsDirectory,
                    businessName = session.businessName,
                    issuerName = session.issuerLegalName,
                    issuerRuc = session.issuerRuc,
                    issuerAddress = session.issuerAddress,
                    issuerCity = session.issuerCity,
                    issuerLogoUrl = session.issuerLogoUrl,
                    issuerSlogan = session.issuerSlogan,
                    issuerPhone = session.issuerPhone,
                    invoice = invoice
                )
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onReady(pdfFile)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Snackbar.make(binding.root, "No se pudo generar el PDF: ${error.localizedMessage ?: "error desconocido"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

package com.tecnoselva.factura.features.billing.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.tecnoselva.factura.features.documents.model.Invoice
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.tecnoselva.factura.features.billing.data.BillingRepository
import com.tecnoselva.factura.features.billing.controller.BillingController
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.documents.data.DocumentsRepository
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.billing.ui.components.InvoiceLinesAdapter
import com.tecnoselva.factura.databinding.ActivityCreateInvoiceBinding
import com.tecnoselva.factura.core.ui.applySystemBarInsets

class CreateInvoiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateInvoiceBinding
    private lateinit var billingRepository: BillingRepository
    private lateinit var documentsRepository: DocumentsRepository
    private lateinit var sessionManager: SessionManager
    private val billingController = BillingController()

    private val documentTypes = arrayOf(
        "Boleta de Venta (03)",
        "Factura (01)",
        "Nota de Crédito (07)",
        "Nota de Débito (08)"
    )
    private var selectedDocumentType = "03"
    private var referenceDocuments: List<Invoice> = emptyList()
    private val creditReasons = linkedMapOf("01" to "Anulación de la operación", "02" to "Anulación por error en el RUC", "03" to "Corrección por error en la descripción", "04" to "Descuento global", "05" to "Descuento por ítem", "06" to "Devolución total", "07" to "Devolución por ítem", "08" to "Bonificación", "09" to "Disminución en el valor", "10" to "Otros conceptos")
    private val debitReasons = linkedMapOf("01" to "Intereses por mora", "02" to "Aumento en el valor", "03" to "Penalidades / otros conceptos", "10" to "Otros conceptos")

    private val lineItems = mutableListOf<InvoiceLineItem>()
    private lateinit var linesAdapter: InvoiceLinesAdapter
    private var currentFormStep = 0
    private var itemsTaxed = true
    private var sendingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionManager(this).isLoggedIn) {
            finish()
            return
        }
        sessionManager = SessionManager(this)
        itemsTaxed = getSharedPreferences("invoice_preferences", MODE_PRIVATE)
            .getBoolean("items_taxed", true)
        val sessionRepository = SessionRepository(sessionManager)
        billingRepository = BillingRepository(sessionRepository)
        documentsRepository = DocumentsRepository(sessionRepository)
        try {
            binding = ActivityCreateInvoiceBinding.inflate(layoutInflater)
            setContentView(binding.root)
            applySystemBarInsets(binding.root)
            setupInvoiceForm()
        } catch (error: Exception) {
            android.util.Log.e("CreateInvoiceActivity", "No se pudo abrir el formulario de facturación", error)
            Toast.makeText(this, "No se pudo abrir Nueva factura: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupInvoiceForm() {

        binding.btnInvoiceNext.setOnClickListener { goToNextStep() }
        binding.btnInvoicePrevious.setOnClickListener {
            if (currentFormStep > 0) {
                currentFormStep--
                renderFormStep()
            }
        }
        renderFormStep()

        binding.switchItemsIgv.isChecked = itemsTaxed
        updateIgvSwitchText()
        binding.switchItemsIgv.setOnCheckedChangeListener { _, isChecked ->
            itemsTaxed = isChecked
            getSharedPreferences("invoice_preferences", MODE_PRIVATE)
                .edit()
                .putBoolean("items_taxed", isChecked)
                .apply()
            lineItems.forEach { it.taxed = isChecked }
            if (::linesAdapter.isInitialized) linesAdapter.notifyDataSetChanged()
            updateTotals()
            updateIgvSwitchText()
        }

        // Setup Spinner
        val docAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, documentTypes)
        docAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDocumentType.adapter = docAdapter

        binding.spinnerDocumentType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedDocumentType = when (position) {
                    1 -> "01"
                    2 -> "07"
                    3 -> "08"
                    else -> "03"
                }
                if (selectedDocumentType == "07" || selectedDocumentType == "08") {
                    binding.layoutReferenceDoc.visibility = View.VISIBLE
                } else {
                    binding.layoutReferenceDoc.visibility = View.GONE
                }
                updateHintForDocType()
                updateReferenceOptions()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        referenceDocuments = emptyList()
        binding.spinnerReferenceDoc.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, referenceDocuments.map { "${if (it.documentType == "01") "Factura" else "Boleta"} · ${it.id} · ${it.clientName} · S/ ${"%.2f".format(it.totalAmount)}" }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        updateReferenceOptions()
        loadReferenceDocuments()

        // En pantalla se muestra solo el detalle; el formulario vive en un modal.
        linesAdapter = InvoiceLinesAdapter(
            items = lineItems,
            onEdit = { position -> showItemDialog(position) },
            onDelete = { position ->
                lineItems.removeAt(position)
                linesAdapter.notifyItemRemoved(position)
                updateTotals()
                updateItemsEmptyState()
            }
        )
        binding.rvLineItems.layoutManager = LinearLayoutManager(this)
        binding.rvLineItems.adapter = linesAdapter

        binding.btnAddItem.setOnClickListener {
            showItemDialog()
        }
        updateTotals()
        updateItemsEmptyState()

        // Auto-lookup con TextWatcher
        binding.etClientRuc.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val docNum = s.toString().trim()
                val docType = if (selectedDocumentType == "01" || docNum.length == 11) "6" else "1"

                if ((docType == "1" && docNum.length == 8) || (docType == "6" && docNum.length == 11)) {
                    performCustomerLookup(docType, docNum)
                }
            }
        })

        binding.btnSendInvoice.setOnClickListener {
            val clientDocNumber = binding.etClientRuc.text.toString().trim()
            val clientName = binding.etClientName.text.toString().trim()
            val clientAddress = binding.etClientAddress.text.toString().trim()

            if (selectedDocumentType != "07" && selectedDocumentType != "08" && (clientDocNumber.isEmpty() || clientName.isEmpty())) {
                Toast.makeText(this, "Por favor complete los datos del cliente", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if ((selectedDocumentType == "07" || selectedDocumentType == "08") && referenceDocuments.isEmpty()) {
                Toast.makeText(this, "No hay facturas o boletas para asociar a la nota", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (selectedDocumentType == "07" || selectedDocumentType == "08") {
                val ref = referenceDocuments.getOrNull(binding.spinnerReferenceDoc.selectedItemPosition)
                if (ref == null) { Toast.makeText(this, "Selecciona el comprobante de referencia", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                binding.etClientRuc.setText(ref.clientRuc)
                binding.etClientName.setText(ref.clientName)
                binding.etClientAddress.setText(ref.customerAddress)
            }

            if (selectedDocumentType == "01" && clientDocNumber.length != 11) {
                Toast.makeText(this, "La Factura (01) requiere un RUC válido de 11 dígitos", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val validItems = billingController.validItems(lineItems)
            if (validItems.isEmpty()) {
                Toast.makeText(this, "Debe agregar al menos un ítem válido con descripción, cantidad y precio", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val refDocument = if (selectedDocumentType == "07" || selectedDocumentType == "08") referenceDocuments.getOrNull(binding.spinnerReferenceDoc.selectedItemPosition) else null
            val docNumberToSend = refDocument?.clientRuc ?: clientDocNumber
            val nameToSend = refDocument?.clientName ?: clientName
            val customerDocType = if (docNumberToSend.length == 11) "6" else if (docNumberToSend.length == 8) "1" else "0"
            if (customerDocType == "0") { Toast.makeText(this, "Usa un RUC de 11 dígitos o DNI de 8 dígitos", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (selectedDocumentType == "01" && customerDocType != "6") { Toast.makeText(this, "La factura requiere RUC", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val reasonMap = if (selectedDocumentType == "07") creditReasons else debitReasons
            val reasonCode = reasonMap.keys.elementAtOrNull(binding.spinnerNoteReason.selectedItemPosition)
            val refPayload = if (refDocument != null && reasonCode != null) JsonObject().apply {
                addProperty("type", refDocument.documentType)
                val parts = refDocument.id.split("-")
                addProperty("series", parts.firstOrNull() ?: "")
                addProperty("number", parts.getOrNull(1)?.toLongOrNull()?.toString() ?: "")
                addProperty("reasonCode", reasonCode)
                addProperty("reasonDescription", reasonMap[reasonCode])
                addProperty("reference_document_id", refDocument.documentUuid)
            } else null

            binding.btnSendInvoice.isEnabled = false
            binding.btnSendInvoice.text = "Enviando…"
            showSendingDialog()

            try {
                billingRepository.saveInvoiceComprobante(
                    documentType = selectedDocumentType,
                    customerDocType = customerDocType,
                    clientRuc = docNumberToSend,
                    clientName = nameToSend,
                    clientAddress = refDocument?.customerAddress ?: clientAddress,
                    lineItems = if (refDocument != null) listOf(InvoiceLineItem("Por los conceptos facturados", 1.0, refDocument.subtotal, "bien", refDocument.igvAmount > 0)) else validItems,
                    reference = refPayload,
                    observations = if (refDocument == null) binding.etObservations.text?.toString()?.trim() else reasonMap[reasonCode]
                ) { result ->
                    runOnUiThread {
                        hideSendingDialog()
                        binding.btnSendInvoice.isEnabled = true
                        binding.btnSendInvoice.text = "Enviar a SUNAT"

                        if (result.success) {
                            Toast.makeText(this, result.message ?: "Comprobante enviado", Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            android.util.Log.e("BillingFlow", "Falló emisión de comprobante: ${result.message}")
                            Toast.makeText(this, result.message ?: "Error al emitir el comprobante", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (error: Exception) {
                hideSendingDialog()
                binding.btnSendInvoice.isEnabled = true
                binding.btnSendInvoice.text = "Enviar a SUNAT"
                android.util.Log.e("BillingFlow", "No se pudo iniciar la emisión", error)
                Toast.makeText(this, error.localizedMessage ?: "No se pudo enviar el comprobante", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSendingDialog() {
        if (sendingDialog?.isShowing == true || isFinishing || isDestroyed) return
        val content = layoutInflater.inflate(com.tecnoselva.factura.R.layout.dialog_sending_invoice, null)
        sendingDialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
                dialog.show()
            }
    }

    private fun hideSendingDialog() {
        sendingDialog?.dismiss()
        sendingDialog = null
    }

    override fun onDestroy() {
        hideSendingDialog()
        super.onDestroy()
    }

    private fun goToNextStep() {
        val canContinue = when (currentFormStep) {
            0 -> validateClientStep()
            1 -> validateItemsStep()
            else -> false
        }
        if (!canContinue) return

        if (currentFormStep < 2) {
            currentFormStep++
            if (currentFormStep == 2) renderReviewDraft()
            renderFormStep()
        }
    }

    private fun validateClientStep(): Boolean {
        binding.etClientRuc.error = null
        binding.etClientName.error = null

        if (selectedDocumentType == "07" || selectedDocumentType == "08") {
            if (referenceDocuments.isEmpty()) {
                Toast.makeText(this, "No hay comprobantes disponibles para asociar a la nota", Toast.LENGTH_LONG).show()
                return false
            }
            if (referenceDocuments.getOrNull(binding.spinnerReferenceDoc.selectedItemPosition) == null) {
                Toast.makeText(this, "Selecciona el comprobante de referencia", Toast.LENGTH_SHORT).show()
                return false
            }
            return true
        }

        val document = binding.etClientRuc.text?.toString()?.trim().orEmpty()
        val name = binding.etClientName.text?.toString()?.trim().orEmpty()

        if (document.isEmpty()) {
            binding.etClientRuc.error = "Ingresa el documento del cliente"
            binding.etClientRuc.requestFocus()
            return false
        }
        if (selectedDocumentType == "01" && document.length != 11) {
            binding.etClientRuc.error = "La factura requiere un RUC de 11 dígitos"
            binding.etClientRuc.requestFocus()
            return false
        }
        if (selectedDocumentType != "01" && document.length != 8 && document.length != 11) {
            binding.etClientRuc.error = "Ingresa un DNI de 8 o RUC de 11 dígitos"
            binding.etClientRuc.requestFocus()
            return false
        }
        if (name.isEmpty() || name.startsWith("Buscando", ignoreCase = true)) {
            binding.etClientName.error = if (name.isEmpty()) "Ingresa el nombre o razón social" else "Espera a que termine la búsqueda"
            binding.etClientName.requestFocus()
            return false
        }
        return true
    }

    private fun validateItemsStep(): Boolean {
        if (billingController.validItems(lineItems).isEmpty()) {
            Toast.makeText(this, "Agrega al menos un ítem con descripción, cantidad y precio", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun showItemDialog(position: Int? = null) {
        val currentItem = position?.let(lineItems::get)
        val dialogView = layoutInflater.inflate(com.tecnoselva.factura.R.layout.dialog_invoice_item, null)
        val descriptionInput = dialogView.findViewById<EditText>(com.tecnoselva.factura.R.id.dialogEtDescription)
        val quantityInput = dialogView.findViewById<EditText>(com.tecnoselva.factura.R.id.dialogEtQuantity)
        val priceInput = dialogView.findViewById<EditText>(com.tecnoselva.factura.R.id.dialogEtUnitPrice)
        val itemTypeButton = dialogView.findViewById<MaterialButton>(com.tecnoselva.factura.R.id.dialogBtnItemType)

        var itemType = currentItem?.itemType ?: "bien"
        descriptionInput.setText(currentItem?.description.orEmpty())
        quantityInput.setText(currentItem?.quantity?.let(::formatEditableNumber) ?: "1")
        priceInput.setText(currentItem?.unitPrice?.takeIf { it > 0.0 }?.let(::formatEditableNumber).orEmpty())

        fun refreshOptions() {
            itemTypeButton.text = if (itemType == "bien") "Producto · Unidad (NIU)" else "Servicio · Unidad (ZZ)"
        }
        refreshOptions()
        itemTypeButton.setOnClickListener {
            itemType = if (itemType == "bien") "servicio" else "bien"
            refreshOptions()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (position == null) "Agregar ítem" else "Editar ítem")
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (position == null) "Agregar" else "Guardar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val description = descriptionInput.text?.toString()?.trim().orEmpty()
                val quantity = quantityInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                val unitPrice = priceInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                when {
                    description.isEmpty() -> {
                        descriptionInput.error = "Describe el producto o servicio"
                        descriptionInput.requestFocus()
                    }
                    quantity <= 0.0 -> {
                        quantityInput.error = "Ingresa una cantidad mayor a cero"
                        quantityInput.requestFocus()
                    }
                    unitPrice <= 0.0 -> {
                        priceInput.error = "Ingresa un precio mayor a cero"
                        priceInput.requestFocus()
                    }
                    else -> {
                        val savedItem = InvoiceLineItem(description, quantity, unitPrice, itemType, itemsTaxed)
                        if (position == null) {
                            lineItems.add(savedItem)
                            linesAdapter.notifyItemInserted(lineItems.lastIndex)
                        } else {
                            lineItems[position] = savedItem
                            linesAdapter.notifyItemChanged(position)
                        }
                        updateTotals()
                        updateItemsEmptyState()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateItemsEmptyState() {
        binding.tvEmptyItems.visibility = if (lineItems.isEmpty()) View.VISIBLE else View.GONE
        binding.rvLineItems.visibility = if (lineItems.isEmpty()) View.GONE else View.VISIBLE
        binding.tvItemsCount.text = if (lineItems.size == 1) "1 ítem agregado" else "${lineItems.size} ítems agregados"
    }

    private fun updateIgvSwitchText() {
        binding.switchItemsIgv.text = if (itemsTaxed) {
            "Precios afectos a IGV (18%)"
        } else {
            "Precios exonerados de IGV"
        }
    }

    private fun formatEditableNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    private fun renderReviewDraft() {
        val reference = if (selectedDocumentType == "07" || selectedDocumentType == "08") {
            referenceDocuments.getOrNull(binding.spinnerReferenceDoc.selectedItemPosition)
        } else null
        val documentLabel = when (selectedDocumentType) {
            "01" -> "FACTURA ELECTRÓNICA"
            "07" -> "NOTA DE CRÉDITO ELECTRÓNICA"
            "08" -> "NOTA DE DÉBITO ELECTRÓNICA"
            else -> "BOLETA DE VENTA ELECTRÓNICA"
        }
        val clientName = reference?.clientName ?: binding.etClientName.text?.toString()?.trim().orEmpty()
        val clientDocument = reference?.clientRuc ?: binding.etClientRuc.text?.toString()?.trim().orEmpty()
        val clientAddress = reference?.customerAddress ?: binding.etClientAddress.text?.toString()?.trim().orEmpty()

        binding.tvReviewDocumentType.text = documentLabel
        binding.tvReviewClientName.text = clientName
        binding.tvReviewClientDocument.text = "${if (clientDocument.length == 11) "RUC" else "DNI"}: $clientDocument"
        binding.tvReviewClientAddress.text = clientAddress.ifEmpty { "Dirección no registrada" }

        binding.reviewItemsContainer.removeAllViews()
        lineItems.forEach { item ->
            val row = layoutInflater.inflate(
                com.tecnoselva.factura.R.layout.item_invoice_review,
                binding.reviewItemsContainer,
                false
            )
            row.findViewById<TextView>(com.tecnoselva.factura.R.id.tvReviewItemDescription).text = item.description
            val unit = if (item.itemType == "bien") "NIU" else "ZZ"
            val tax = if (item.taxed) "Afecto IGV" else "Exonerado"
            row.findViewById<TextView>(com.tecnoselva.factura.R.id.tvReviewItemMeta).text =
                "${formatEditableNumber(item.quantity)} $unit × S/ ${formatMoney(item.unitPrice)} · $tax"
            row.findViewById<TextView>(com.tecnoselva.factura.R.id.tvReviewItemTotal).text =
                "S/ ${formatMoney(item.quantity * item.unitPrice)}"
            binding.reviewItemsContainer.addView(row)
        }
        updateTotals()
    }

    private fun formatMoney(value: Double): String {
        return String.format(java.util.Locale.US, "%.2f", value)
    }

    private fun renderFormStep() {
        binding.panelClient.visibility = if (currentFormStep == 0) View.VISIBLE else View.GONE
        binding.panelItems.visibility = if (currentFormStep == 1) View.VISIBLE else View.GONE
        binding.panelReview.visibility = if (currentFormStep == 2) View.VISIBLE else View.GONE

        binding.btnInvoicePrevious.visibility = if (currentFormStep == 0) View.INVISIBLE else View.VISIBLE
        binding.btnInvoiceNext.visibility = if (currentFormStep < 2) View.VISIBLE else View.GONE
        binding.btnSendInvoice.visibility = if (currentFormStep == 2) View.VISIBLE else View.GONE
        binding.layoutBottomTotal.visibility = if (currentFormStep == 1) View.VISIBLE else View.INVISIBLE

        styleStepIndicator(binding.stepClientIndicator, 0)
        styleStepIndicator(binding.stepItemsIndicator, 1)
        styleStepIndicator(binding.stepReviewIndicator, 2)
    }

    private fun styleStepIndicator(indicator: TextView, step: Int) {
        val isReached = step <= currentFormStep
        val backgroundColor = if (isReached) com.tecnoselva.factura.R.color.brand_navy else com.tecnoselva.factura.R.color.brand_surface_subtle
        val textColor = if (isReached) com.tecnoselva.factura.R.color.white else com.tecnoselva.factura.R.color.brand_text_secondary
        indicator.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColor))
        indicator.setTextColor(ContextCompat.getColor(this, textColor))
        indicator.text = if (step < currentFormStep) "✓" else (step + 1).toString()
    }

    private fun loadReferenceDocuments() {
        Thread {
            try {
                val docs = documentsRepository.getReferenceDocuments()
                    .filter { it.status == "accepted" || it.status == "accepted_with_observations" || it.status == "observed" }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    referenceDocuments = docs
                    updateReferenceOptions()
                }
            } catch (error: Exception) {
                android.util.Log.e("CreateInvoiceActivity", "Error cargando comprobantes de referencia", error)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, "No se pudieron cargar comprobantes de referencia: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun updateReferenceOptions() {
        val isNote = selectedDocumentType == "07" || selectedDocumentType == "08"
        if (::binding.isInitialized) binding.layoutReferenceDoc.visibility = if (isNote) View.VISIBLE else View.GONE
        val reasons = if (selectedDocumentType == "08") debitReasons else creditReasons
        binding.spinnerNoteReason.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reasons.values.toList()).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (referenceDocuments.isNotEmpty()) {
            val filtered = referenceDocuments.filter { it.documentType == "01" || it.documentType == "03" }
            binding.spinnerReferenceDoc.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filtered.map { "${it.documentType} · ${it.id} · ${it.clientName} · S/ ${"%.2f".format(it.totalAmount)}" }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            referenceDocuments = filtered
        }
    }

    private fun updateTotals() {
        val totals = billingController.calculateTotals(lineItems)
        binding.tvSubtotalTaxed.text = "Subtotal afecto: S/ ${"%.2f".format(java.util.Locale.US, totals.taxableSubtotal)}"
        binding.tvSubtotalExempt.text = "Subtotal exonerado: S/ ${"%.2f".format(java.util.Locale.US, totals.exemptSubtotal)}"
        binding.tvIgvAmount.text = "IGV (18%): S/ ${"%.2f".format(java.util.Locale.US, totals.igv)}"
        binding.tvTotalAmount.text = "TOTAL: S/ ${"%.2f".format(java.util.Locale.US, totals.total)}"
        binding.tvBottomTotal.text = "S/ ${"%.2f".format(java.util.Locale.US, totals.total)}"
    }

    private fun updateHintForDocType() {
        if (selectedDocumentType == "01") {
            binding.layoutClientDocument.hint = "RUC de la empresa (11 dígitos)"
        } else {
            binding.layoutClientDocument.hint = "DNI (8) o RUC (11) del cliente"
        }
        binding.etClientRuc.hint = null
    }

    private fun performCustomerLookup(docType: String, docNumber: String) {
        binding.etClientName.setText("Buscando en SUNAT/RENIEC...")
        billingRepository.lookupCustomer(docType, docNumber) { result ->
            runOnUiThread {
                if (result.success && !result.name.isNullOrEmpty()) {
                    binding.etClientName.setText(result.name)
                    if (!result.address.isNullOrEmpty()) {
                        binding.etClientAddress.setText(result.address)
                    }
                    Toast.makeText(this, "¡Datos encontrados automáticamente!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.etClientName.setText("")
                    Toast.makeText(this, "No se encontró el documento, ingrese manualmente", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

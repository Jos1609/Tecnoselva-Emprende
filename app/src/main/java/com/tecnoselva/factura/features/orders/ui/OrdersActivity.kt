package com.tecnoselva.factura.features.orders.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.text.InputType
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.databinding.ActivityOrdersBinding
import com.tecnoselva.factura.databinding.DialogOrderDetailBinding
import com.tecnoselva.factura.databinding.ItemOrderDetailLineBinding
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.billing.data.BillingRepository
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.documents.ui.DocumentPreviewActivity
import com.tecnoselva.factura.features.documents.ui.InvoicePdfGenerator
import com.tecnoselva.factura.features.documents.data.DocumentsRepository
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.orders.data.LocalOrderRepository
import com.tecnoselva.factura.features.orders.data.PremiumOrderRepository
import com.tecnoselva.factura.features.orders.model.OrderSummary
import com.tecnoselva.factura.features.orders.ui.components.OrderSummaryAdapter
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class OrdersActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOrdersBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: OrderSummaryAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var currentSearch = ""
    private var loadRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.ORDERS)
        setupNavigation()
        adapter = OrderSummaryAdapter(::showOrder)
        binding.rvOrders.layoutManager = LinearLayoutManager(this)
        binding.rvOrders.adapter = adapter
        binding.btnNewSale.setOnClickListener {
            openTopLevel(PosActivity::class.java)
        }
        binding.etOrderSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearch = s?.toString()?.trim().orEmpty()
                pendingSearch?.let(searchHandler::removeCallbacks)
                pendingSearch = Runnable { loadOrders(currentSearch) }.also {
                    searchHandler.postDelayed(it, 350L)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.swipeOrders.setOnRefreshListener { loadOrders(currentSearch) }
        loadOrders()
    }

    private fun setupNavigation() = with(binding.bottomNavigationOrders) {
        selectedItemId = R.id.nav_sales
        menu.findItem(R.id.nav_billing).isVisible = SessionManager(this@OrdersActivity).isLoggedIn
        setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> openTopLevel(HomeActivity::class.java)
                R.id.nav_sales -> openTopLevel(PosActivity::class.java)
                R.id.nav_inventory -> openTopLevel(LocalCatalogActivity::class.java)
                R.id.nav_billing -> openTopLevel(DocumentsActivity::class.java)
                else -> false
            }
        }
    }

    private fun loadOrders(search: String = currentSearch) {
        val request = ++loadRequest
        binding.swipeOrders.isRefreshing = true
        executor.execute {
            try {
                val orders = if (session.isLoggedIn) {
                    PremiumOrderRepository(SessionRepository(session)).getOrders(search)
                } else {
                    LocalOrderRepository(this, LocalStoreProfileRepository(this).localStoreId).getOrders(search)
                }
                runOnUiThread {
                    if (request != loadRequest) return@runOnUiThread
                    adapter.submitList(orders)
                    binding.tvOrdersEmpty.text = if (search.isBlank()) {
                        "Todavía no hay pedidos registrados."
                    } else {
                        "No se encontraron pedidos para \"$search\"."
                    }
                    binding.tvOrdersEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvOrders.visibility = if (orders.isEmpty()) View.GONE else View.VISIBLE
                    binding.swipeOrders.isRefreshing = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (request != loadRequest) return@runOnUiThread
                    binding.swipeOrders.isRefreshing = false
                    Snackbar.make(binding.root, error.localizedMessage ?: "No se pudieron cargar los pedidos", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showOrder(order: OrderSummary) {
        val detail = DialogOrderDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        var detailDismissed = false
        dialog.setOnDismissListener { detailDismissed = true }
        dialog.setContentView(detail.root)
        bindOrderDetail(detail, order, dialog)
        dialog.show()
        if (session.isLoggedIn) {
            executor.execute {
                try {
                    val loadedOrder = PremiumOrderRepository(SessionRepository(session)).getOrderDetail(order)
                    runOnUiThread {
                        if (!detailDismissed && !isFinishing && !isDestroyed) {
                            bindOrderDetail(detail, loadedOrder, dialog)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!detailDismissed && !isFinishing && !isDestroyed) {
                            Snackbar.make(binding.root, "Detalle básico cargado. No se pudo ampliar desde Supabase.", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun bindOrderDetail(detail: DialogOrderDetailBinding, order: OrderSummary, dialog: BottomSheetDialog) {
        detail.tvOrderDetailNumber.text = order.orderNumber
        detail.tvOrderDetailType.text = documentLabel(order.documentType)
        detail.tvOrderDetailStatus.text = statusLabel(order.status)
        detail.tvOrderDetailPayment.text = paymentLabel(order.paymentStatus)
        detail.tvOrderDetailCustomer.text = order.customerName
        detail.tvOrderDetailCustomerMeta.text = listOf(
            order.customerDocumentType.takeIf { it.isNotBlank() }?.uppercase()?.let { "$it ${order.customerDocumentNumber}" },
            order.customerPhone.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ")
        detail.tvOrderDetailCustomerMeta.visibility = if (detail.tvOrderDetailCustomerMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val hasShipping = order.shippingAddress.isNotBlank() || order.shippingRecipient.isNotBlank() || order.orderType == "delivery"
        detail.cardOrderDetailShipping.visibility = if (hasShipping) View.VISIBLE else View.GONE
        detail.tvOrderDetailShippingName.text = listOf(order.shippingRecipient, order.shippingPhone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Destinatario no registrado" }
        detail.tvOrderDetailShippingAddress.text = listOf(order.shippingAddress, order.shippingReference.takeIf { it.isNotBlank() }?.let { "Ref. $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        detail.tvOrderDetailPaymentMeta.text = listOf(
            paymentMethodLabel(order.paymentMethod).takeIf { it.isNotBlank() },
            "Pagado S/ ${money(order.amountPaid)}",
            "Pendiente S/ ${money(order.amountPending)}"
        ).filterNotNull().joinToString(" · ")
        detail.btnOrderRegisterPayment.visibility = if (session.isLoggedIn && order.amountPending > 0.0 && order.status != "cancelled") View.VISIBLE else View.GONE
        detail.tvOrderDetailTotal.text = "S/ ${String.format(java.util.Locale.US, "%.2f", order.total)}"
        detail.tvOrderDetailTotalsBreakdown.text = listOf(
            "Subtotal S/ ${money(order.subtotal)}",
            "IGV S/ ${money(order.tax)}",
            order.discount.takeIf { it > 0.0 }?.let { "Descuento S/ ${money(it)}" },
            order.shippingCost.takeIf { it > 0.0 }?.let { "Envío S/ ${money(it)}" }
        ).filterNotNull().joinToString("  ·  ")
        detail.llOrderDetailItems.removeAllViews()
        detail.tvOrderDetailItemsEmpty.visibility = if (order.items.isEmpty()) View.VISIBLE else View.GONE
        order.items.forEach { item ->
            val line = ItemOrderDetailLineBinding.inflate(layoutInflater, detail.llOrderDetailItems, false)
            line.tvOrderLineName.text = "${formatQuantity(item.quantity)}x ${item.name}"
            line.tvOrderLineVariant.text = item.variantLabel.takeIf { it.isNotBlank() }?.let { "Variante: $it" }.orEmpty()
            line.tvOrderLineVariant.visibility = if (item.variantLabel.isBlank()) View.GONE else View.VISIBLE
            line.tvOrderLinePrice.text = if (item.unitPrice > 0.0) "S/ ${String.format(java.util.Locale.US, "%.2f", item.unitPrice)} c/u" else ""
            line.tvOrderLinePrice.visibility = if (item.unitPrice > 0.0) View.VISIBLE else View.GONE
            line.tvOrderLineSubtotal.text = if (item.subtotal > 0.0) "S/ ${String.format(java.util.Locale.US, "%.2f", item.subtotal)}" else ""
            detail.llOrderDetailItems.addView(line.root)
        }
        if (order.items.isEmpty() && order.itemsSummary.isNotBlank()) {
            detail.tvOrderDetailItemsEmpty.text = order.itemsSummary
            detail.tvOrderDetailItemsEmpty.visibility = View.VISIBLE
        }
        detail.tvOrderDetailTimeline.text = timeline(order)
        detail.btnCloseOrderDetail.setOnClickListener { dialog.dismiss() }
        val canManage = session.isLoggedIn
        val canAdvance = canManage && order.status != "delivered" && order.status != "cancelled"
        detail.orderStatusDropdownLayout.visibility = if (canAdvance) View.VISIBLE else View.GONE
        detail.btnOrderCancel.visibility = if (canManage && order.status != "delivered" && order.status != "cancelled") View.VISIBLE else View.GONE
        val statusOptions = statusOptions(order)
        detail.actOrderStatus.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, statusOptions.map(::statusLabel)))
        detail.actOrderStatus.setText(statusLabel(order.status), false)
        detail.actOrderStatus.setOnItemClickListener { _, _, position, _ ->
            val selectedStatus = statusOptions.getOrNull(position)
            if (selectedStatus != null && selectedStatus != order.status) {
                dialog.dismiss()
                confirmStatusChange(order, selectedStatus)
            }
        }
        detail.btnOrderCancel.setOnClickListener {
            dialog.dismiss()
            showCancelOrderDialog(order)
        }
        detail.btnOrderRegisterPayment.setOnClickListener {
            dialog.dismiss()
            showPaymentDialog(order)
        }
        detail.btnOrderPdf.setOnClickListener {
            dialog.dismiss()
            generateOrderPdf(order, share = false)
        }
        detail.btnOrderSharePdf.setOnClickListener {
            dialog.dismiss()
            generateOrderPdf(order, share = true)
        }
        val sunatDocumentType = order.documentType.lowercase(Locale.US).let {
            when (it) {
                "factura", "01" -> "01"
                "boleta", "03" -> "03"
                else -> null
            }
        }
        val canSendToSunat = session.isLoggedIn &&
            sunatDocumentType != null &&
            order.status != "cancelled" &&
            order.sunatStatus.isBlank()
        detail.btnOrderSunat.visibility = if (canSendToSunat) View.VISIBLE else View.GONE
        detail.btnOrderSunat.setOnClickListener {
            if (!session.isLoggedIn) return@setOnClickListener
            dialog.dismiss()
            issueOrderDocumentToSunat(order)
        }
    }

    private fun issueOrderDocumentToSunat(order: OrderSummary) {
        if (!session.isLoggedIn) return
        val documentType = when (order.documentType.lowercase(Locale.US)) {
            "factura", "01" -> "01"
            "boleta", "03" -> "03"
            else -> {
                Snackbar.make(binding.root, "Este pedido no tiene boleta o factura seleccionada.", Snackbar.LENGTH_LONG).show()
                return
            }
        }
        if (order.items.isEmpty()) {
            Snackbar.make(binding.root, "El pedido no tiene productos para emitir el comprobante.", Snackbar.LENGTH_LONG).show()
            return
        }
        val customerDocType = when {
            order.customerDocumentType.equals("ruc", ignoreCase = true) || order.customerDocumentType == "6" -> "6"
            order.customerDocumentType.equals("dni", ignoreCase = true) || order.customerDocumentType == "1" -> "1"
            order.customerDocumentType.equals("ce", ignoreCase = true) || order.customerDocumentType == "4" -> "4"
            order.customerDocumentType.equals("pasaporte", ignoreCase = true) || order.customerDocumentType == "7" -> "7"
            order.customerDocumentNumber.length == 11 -> "6"
            else -> "0"
        }
        val sunatCustomerDocType = if (documentType == "01") "6" else customerDocType
        if (sunatCustomerDocType == "0" || order.customerDocumentNumber.isBlank()) {
            Snackbar.make(binding.root, "El pedido no tiene un documento válido del cliente.", Snackbar.LENGTH_LONG).show()
            return
        }
        val includeIgv = order.taxRate > 0.0
        val lineItems = order.items.map { item ->
            InvoiceLineItem(
                description = if (item.itemType == "service" && item.notes.isNotBlank()) {
                    "${item.name} - ${item.notes.trim()}"
                } else {
                    item.name + item.variantLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                },
                quantity = item.quantity,
                unitPrice = if (includeIgv) item.unitPrice / 1.18 else item.unitPrice,
                itemType = if (item.itemType == "service") "servicio" else "bien",
                taxed = includeIgv
            )
        }
        Snackbar.make(binding.root, "Enviando comprobante a SUNAT…", Snackbar.LENGTH_SHORT).show()
        BillingRepository(SessionRepository(session)).saveInvoiceComprobante(
            documentType = documentType,
            customerDocType = sunatCustomerDocType,
            clientRuc = order.customerDocumentNumber,
            clientName = order.customerName,
            clientAddress = order.customerAddress,
            lineItems = lineItems,
            observations = order.notes.ifBlank { "Pedido ${order.orderNumber}" },
            orderId = order.id
        ) { result ->
            runOnUiThread {
                if (result.success) {
                    Snackbar.make(
                        binding.root,
                        "Comprobante enviado a SUNAT${result.documentNumber?.let { ": $it" }.orEmpty()}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    loadOrders()
                } else {
                    Snackbar.make(binding.root, result.message ?: "No se pudo enviar a SUNAT.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun generateOrderPdf(order: OrderSummary, share: Boolean) {
        Snackbar.make(binding.root, "Preparando PDF del pedido…", Snackbar.LENGTH_SHORT).show()
        executor.execute {
            try {
                val documentsDirectory = File(filesDir, "documents")
                val pdf = if (session.isLoggedIn) {
                    val documentsRepository = DocumentsRepository(SessionRepository(session))
                    val electronicInvoice = documentsRepository.getDocumentByOrderId(order.id)
                    if (electronicInvoice != null) {
                        val issuer = documentsRepository.getIssuerDetails()
                        session.issuerRuc = issuer["ruc"].orEmpty().ifBlank { session.issuerRuc }
                        session.issuerLegalName = issuer["legalName"].orEmpty().ifBlank { session.issuerLegalName }
                        session.issuerAddress = issuer["address"].orEmpty().ifBlank { session.issuerAddress }
                        session.issuerCity = issuer["city"].orEmpty().ifBlank { session.issuerCity }
                        session.issuerLogoUrl = issuer["logoUrl"].orEmpty().ifBlank { session.issuerLogoUrl }
                        session.issuerSlogan = issuer["slogan"].orEmpty().ifBlank { session.issuerSlogan }
                        session.issuerPhone = issuer["phone"].orEmpty().ifBlank { session.issuerPhone }
                        InvoicePdfGenerator.create(
                            documentsDirectory = documentsDirectory,
                            businessName = session.businessName,
                            issuerName = session.issuerLegalName,
                            issuerRuc = session.issuerRuc,
                            issuerAddress = session.issuerAddress,
                            issuerCity = session.issuerCity,
                            issuerLogoUrl = session.issuerLogoUrl,
                            issuerSlogan = session.issuerSlogan,
                            issuerPhone = session.issuerPhone,
                            invoice = electronicInvoice
                        )
                    } else {
                        OrderPdfGenerator.create(documentsDirectory, session, order)
                    }
                } else {
                    OrderPdfGenerator.create(documentsDirectory, session, order)
                }
                runOnUiThread {
                    if (share) {
                        runCatching {
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Compartir pedido PDF"))
                        }.onFailure { error -> Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo compartir el PDF.", Snackbar.LENGTH_LONG).show() }
                    } else {
                        startActivity(Intent(this, DocumentPreviewActivity::class.java).apply {
                            putExtra(DocumentPreviewActivity.EXTRA_PDF_PATH, pdf.absolutePath)
                            putExtra(DocumentPreviewActivity.EXTRA_TITLE, order.orderNumber)
                        })
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo generar el PDF.", Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    private fun showPaymentDialog(order: OrderSummary) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val amount = EditText(this).apply {
            hint = "Monto del abono"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", order.amountPending))
            selectAll()
        }
        val reference = EditText(this).apply {
            hint = "Referencia (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        form.addView(amount)
        form.addView(reference)
        MaterialAlertDialogBuilder(this)
            .setTitle("Registrar abono")
            .setMessage("Pendiente: S/ ${money(order.amountPending)}")
            .setView(form)
            .setNegativeButton("Volver", null)
            .setPositiveButton("Guardar pago") { _, _ ->
                val paid = amount.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                if (paid <= 0.0 || paid > order.amountPending + 0.01) {
                    Snackbar.make(binding.root, "Ingresa un monto válido, hasta S/ ${money(order.amountPending)}.", Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                executor.execute {
                    try {
                        val ok = PremiumOrderRepository(SessionRepository(session)).updateOrderPayment(order.id, order.amountPaid + paid, reference.text?.toString().orEmpty())
                        runOnUiThread {
                            if (ok) {
                                Snackbar.make(binding.root, "Pago registrado.", Snackbar.LENGTH_SHORT).show()
                                loadOrders()
                            } else Snackbar.make(binding.root, "No se pudo registrar el pago.", Snackbar.LENGTH_LONG).show()
                        }
                    } catch (error: Exception) {
                        runOnUiThread { Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo registrar el pago.", Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
    }

    private fun confirmStatusChange(order: OrderSummary, newStatus: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Actualizar pedido")
            .setMessage("¿Cambiar ${order.orderNumber} a ${statusLabel(newStatus)}?")
            .setNegativeButton("Volver", null)
            .setPositiveButton("Actualizar") { _, _ ->
                executor.execute {
                    try {
                        val ok = PremiumOrderRepository(SessionRepository(session)).updateOrderStatus(order.id, newStatus, session.currentUserId)
                        runOnUiThread {
                            if (ok) {
                                Snackbar.make(binding.root, "Pedido actualizado.", Snackbar.LENGTH_SHORT).show()
                                loadOrders()
                            } else Snackbar.make(binding.root, "No se pudo actualizar el pedido.", Snackbar.LENGTH_LONG).show()
                        }
                    } catch (error: Exception) {
                        runOnUiThread { Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo actualizar el pedido.", Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
    }

    private fun showCancelOrderDialog(order: OrderSummary) {
        val input = EditText(this).apply { hint = "Motivo de cancelación"; minLines = 2 }
        MaterialAlertDialogBuilder(this)
            .setTitle("Cancelar pedido")
            .setMessage("Esta acción restaura el stock según las reglas de AdminStore.")
            .setView(input)
            .setNegativeButton("Volver", null)
            .setPositiveButton("Cancelar pedido") { _, _ ->
                executor.execute {
                    try {
                        val ok = PremiumOrderRepository(SessionRepository(session)).cancelOrder(order.id, input.text?.toString().orEmpty(), session.currentUserId)
                        runOnUiThread {
                            if (ok) {
                                Snackbar.make(binding.root, "Pedido cancelado.", Snackbar.LENGTH_SHORT).show()
                                loadOrders()
                            } else Snackbar.make(binding.root, "No se pudo cancelar el pedido.", Snackbar.LENGTH_LONG).show()
                        }
                    } catch (error: Exception) {
                        runOnUiThread { Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo cancelar el pedido.", Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
    }

    private fun statusLabel(value: String) = when (value) {
        "pending" -> "Pendiente"
        "confirmed" -> "Confirmado"
        "preparing" -> "Preparando"
        "ready" -> "Listo"
        "shipped" -> "Enviado"
        "cancelled" -> "Cancelado"
        else -> "Entregado"
    }

    private fun paymentLabel(value: String) = when (value) {
        "paid" -> "Pagado"
        "partial" -> "Pago parcial"
        "refunded" -> "Reembolsado"
        else -> "Pendiente"
    }

    private fun documentLabel(value: String) = when (value) {
        "factura" -> "Factura"
        "boleta" -> "Boleta"
        else -> "Nota de venta"
    }

    private fun paymentMethodLabel(value: String) = when (value) {
        "cash" -> "Efectivo"
        "card" -> "Tarjeta"
        "transfer" -> "Transferencia"
        "yape" -> "Yape"
        "plin" -> "Plin"
        "paypal" -> "PayPal"
        "other" -> "Otro"
        else -> ""
    }

    private fun statusOptions(order: OrderSummary): List<String> {
        val flow = listOf("pending", "confirmed", "preparing", "ready", "shipped", "delivered")
        return flow.filterNot { it == "shipped" && order.orderType == "pickup" }
    }

    private fun timeline(order: OrderSummary): String = listOf(
        "Creado" to order.createdAt,
        "Confirmado" to order.confirmedAt,
        "Preparado" to order.preparedAt,
        "Enviado" to order.shippedAt,
        "Entregado" to order.deliveredAt,
        "Cancelado" to order.cancelledAt
    ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first} · ${formatDateTime(it.second)}" }
        .ifBlank { "Sin movimientos adicionales registrados." }

    private fun formatDateTime(value: String): String = runCatching {
        val date = value.toLongOrNull()?.let(::Date)
            ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(value)
        date?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(it) } ?: value
    }.getOrDefault(value)

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun formatQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(java.util.Locale.US, value)

    private fun openTopLevel(destination: Class<out AppCompatActivity>): Boolean {
        startActivity(Intent(this, destination).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP })
        applyNavigationTransition()
        finish()
        return true
    }



    override fun onDestroy() {
        pendingSearch?.let(searchHandler::removeCallbacks)
        executor.shutdownNow()
        super.onDestroy()
    }
}

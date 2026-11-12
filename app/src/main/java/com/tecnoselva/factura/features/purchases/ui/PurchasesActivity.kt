package com.tecnoselva.factura.features.purchases.ui

import android.content.Intent
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.databinding.ActivityPurchasesBinding
import com.tecnoselva.factura.databinding.DialogPurchaseDetailBinding
import com.tecnoselva.factura.databinding.ItemPurchaseDetailLineBinding
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.catalog.ui.CatalogNavigation
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.purchases.data.LocalPurchasesRepository
import com.tecnoselva.factura.features.purchases.data.PurchaseDataSource
import com.tecnoselva.factura.features.purchases.data.PurchasesRepository
import com.tecnoselva.factura.features.purchases.model.Purchase
import com.tecnoselva.factura.features.purchases.model.PurchaseDetail
import com.google.gson.Gson
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PurchasesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPurchasesBinding
    private lateinit var session: SessionManager
    private lateinit var repository: PurchaseDataSource
    private lateinit var adapter: PurchaseSummaryAdapter
    private var dateFilter = DateFilter.WEEK
    private var selectedDay = Calendar.getInstance()
    private var loadingDetailId: String? = null

    private enum class DateFilter { DAY, WEEK, MONTH, YEAR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchasesBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.PURCHASES)
        session = SessionManager(this)
        repository = if (session.isLoggedIn && session.storeId.isNotBlank()) {
            PurchasesRepository(SessionRepository(session), this)
        } else {
            LocalPurchasesRepository(this, LocalStoreProfileRepository(this).localStoreId)
        }
        adapter = PurchaseSummaryAdapter(
            onAction = { purchase, action -> updatePurchase(purchase.id, action) },
            onOpen = ::showPurchaseDetail
        )
        binding.rvPurchases.layoutManager = LinearLayoutManager(this)
        binding.rvPurchases.adapter = adapter
        setupDateFilters()
        binding.bottomNavigationPurchases.selectedItemId = R.id.nav_inventory
        binding.bottomNavigationPurchases.menu.findItem(R.id.nav_billing).isVisible = session.isLoggedIn
        binding.bottomNavigationPurchases.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> openTopLevel(HomeActivity::class.java)
                R.id.nav_sales -> openTopLevel(PosActivity::class.java)
                R.id.nav_inventory -> { startActivity(Intent(this, LocalCatalogActivity::class.java)); finish(); true }
                R.id.nav_billing -> openTopLevel(DocumentsActivity::class.java)
                else -> false
            }
        }
        binding.fabNewPurchase.setOnClickListener { startActivity(Intent(this, PurchaseWizardActivity::class.java)) }
        binding.swipePurchases.setOnRefreshListener { loadPurchases() }
        binding.etPurchaseSearch.setOnEditorActionListener { _, _, _ -> loadPurchases(); true }
        loadPurchases()
    }

    private fun setupDateFilters() {
        binding.layoutPurchaseSearch.setEndIconOnClickListener { showDateFilterDialog() }
    }

    private fun showDateFilterDialog() {
        val options = arrayOf("Día", "Semana", "Mes", "Año")
        val selectedIndex = when (dateFilter) {
            DateFilter.DAY -> 0
            DateFilter.WEEK -> 1
            DateFilter.MONTH -> 2
            DateFilter.YEAR -> 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Filtrar compras por fecha")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                dateFilter = when (which) {
                    0 -> DateFilter.DAY
                    2 -> DateFilter.MONTH
                    3 -> DateFilter.YEAR
                    else -> DateFilter.WEEK
                }
                dialog.dismiss()
                if (dateFilter == DateFilter.DAY) showDayPicker() else loadPurchases()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDayPicker() {
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            selectedDay = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
            }
            dateFilter = DateFilter.DAY
            loadPurchases()
        }, selectedDay.get(Calendar.YEAR), selectedDay.get(Calendar.MONTH), selectedDay.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("Selecciona el día de compra")
            show()
        }
    }

    private fun loadPurchases() {
        binding.swipePurchases.isRefreshing = true
        val query = binding.etPurchaseSearch.text?.toString()
        val (dateFrom, dateTo) = selectedDateRange()
        thread {
            val result = runCatching { repository.getPurchases(query, dateFrom, dateTo) }
            runOnUiThread {
                binding.swipePurchases.isRefreshing = false
                result.onSuccess { rows ->
                    adapter.submitList(rows)
                    binding.tvPurchasesEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure { error -> Snackbar.make(binding.root, error.localizedMessage ?: "No se pudieron cargar las compras.", Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    private fun selectedDateRange(): Pair<String, String> {
        val end = if (dateFilter == DateFilter.DAY) {
            selectedDay.clone() as Calendar
        } else {
            Calendar.getInstance()
        }
        val start = (end.clone() as Calendar)
        when (dateFilter) {
            DateFilter.DAY -> start.timeInMillis = selectedDay.timeInMillis
            DateFilter.WEEK -> {
                val mondayOffset = (start.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                start.add(Calendar.DAY_OF_MONTH, -mondayOffset)
            }
            DateFilter.MONTH -> {
                start.set(Calendar.DAY_OF_MONTH, 1)
            }
            DateFilter.YEAR -> {
                start.set(Calendar.MONTH, Calendar.JANUARY)
                start.set(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return formatDate(start) to formatDate(end)
    }

    private fun formatDate(calendar: Calendar): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

    private fun updatePurchase(id: String, action: String) {
        thread {
            val ok = runCatching { if (action == "receive") repository.receivePurchase(id) else repository.cancelPurchase(id) }.getOrDefault(false)
            runOnUiThread {
                Snackbar.make(binding.root, if (ok) "Compra actualizada." else "No se pudo actualizar la compra.", Snackbar.LENGTH_LONG).show()
                if (ok) loadPurchases()
            }
        }
    }

    private fun showPurchaseDetail(purchase: Purchase) {
        if (loadingDetailId != null) return
        loadingDetailId = purchase.id
        binding.swipePurchases.isRefreshing = true
        thread {
            val result = runCatching { repository.getPurchaseDetail(purchase.id) }
            runOnUiThread {
                binding.swipePurchases.isRefreshing = false
                loadingDetailId = null
                result.onSuccess { detail ->
                    if (detail == null) {
                        Snackbar.make(binding.root, "No se pudo cargar los productos de la compra.", Snackbar.LENGTH_LONG).show()
                    } else {
                        startActivity(Intent(this, PurchaseDetailActivity::class.java).apply {
                            putExtra(PurchaseDetailActivity.EXTRA_ID, purchase.id)
                            putExtra(PurchaseDetailActivity.EXTRA_DATE, purchase.purchaseDate)
                            putExtra(PurchaseDetailActivity.EXTRA_SUPPLIER, purchase.supplierName)
                            putExtra(PurchaseDetailActivity.EXTRA_INVOICE, purchase.invoiceNumber)
                            putExtra(PurchaseDetailActivity.EXTRA_TOTAL, purchase.total)
                            putExtra(PurchaseDetailActivity.EXTRA_STATUS, purchase.status)
                            putExtra(PurchaseDetailActivity.EXTRA_NOTES, purchase.notes)
                            putExtra(PurchaseDetailActivity.EXTRA_DETAIL_JSON, Gson().toJson(detail))
                        })
                        applyNavigationTransition()
                    }
                }.onFailure {
                    Snackbar.make(binding.root, "No se pudo cargar los productos de la compra.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindPurchaseDetail(detail: DialogPurchaseDetailBinding, data: PurchaseDetail, dialog: BottomSheetDialog) {
        val purchase = data.purchase
        detail.tvPurchaseDetailSupplier.text = purchase.supplierName.ifBlank { "Sin proveedor" }
        detail.tvPurchaseDetailStatus.text = statusLabel(purchase.status)
        detail.tvPurchaseDetailDocument.text = purchase.invoiceNumber.ifBlank { "Compra sin documento" }
        detail.tvPurchaseDetailDate.text = formatDate(purchase.purchaseDate)
        detail.tvPurchaseDetailRuc.text = data.supplierRuc.ifBlank { "No registrado" }
        detail.tvPurchaseDetailSupplierMeta.text = listOf(
            purchase.supplierName.ifBlank { "Proveedor no registrado" },
            purchase.invoiceNumber.takeIf { it.isNotBlank() }?.let { "Documento $it" }
        ).filterNotNull().joinToString(" · ")

        detail.purchaseDetailItems.removeAllViews()
        detail.tvPurchaseDetailItemsEmpty.visibility = if (data.items.isEmpty()) View.VISIBLE else View.GONE
        data.items.forEach { item ->
            val line = ItemPurchaseDetailLineBinding.inflate(layoutInflater, detail.purchaseDetailItems, false)
            line.tvPurchaseDetailLineName.text = "${formatQuantity(item.quantity)} × ${item.productName}${item.variantLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
            line.tvPurchaseDetailLineMeta.text = listOf(
                item.sku.takeIf { it.isNotBlank() }?.let { "SKU $it" },
                "S/ ${money(item.unitCost)} c/u"
            ).filterNotNull().joinToString(" · ")
            line.tvPurchaseDetailLineSubtotal.text = "S/ ${money(item.totalCost)}"
            line.tvPurchaseDetailLineStock.text = if (purchase.status == "received" && item.previousStock != null) {
                "Stock anterior: ${formatQuantity(item.previousStock)} → Nuevo: ${formatQuantity(item.currentStock ?: (item.previousStock + item.quantity))}"
            } else {
                "Stock actual: ${item.currentStock?.let(::formatQuantity) ?: "No disponible"}"
            }
            line.tvPurchaseDetailLineStock.visibility = View.VISIBLE
            detail.purchaseDetailItems.addView(line.root)
        }

        val gross = data.items.sumOf { it.totalCost }.takeIf { it > 0.0 } ?: (purchase.total + data.discount)
        val discount = data.discount.coerceIn(0.0, gross)
        val netTotal = purchase.total
        val subtotal = data.subtotal.takeIf { it > 0.0 } ?: (netTotal / 1.18)
        val tax = data.tax.takeIf { it > 0.0 } ?: (netTotal - subtotal)
        detail.tvPurchaseDetailTotal.text = "S/ ${money(netTotal)}"
        detail.tvPurchaseDetailTotalsBreakdown.text = listOf(
            "Productos S/ ${money(gross)}",
            discount.takeIf { it > 0.0 }?.let { "Descuento S/ ${money(it)}" },
            "Subtotal S/ ${money(subtotal)}",
            "IGV S/ ${money(tax)}"
        ).filterNotNull().joinToString("  ·  ")

        val notes = visiblePurchaseNotes(purchase.notes, purchase.supplierName)
        detail.cardPurchaseDetailNotes.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
        detail.tvPurchaseDetailNotes.text = notes
        detail.cardPurchaseDetailReceived.visibility = if (purchase.status == "received") View.VISIBLE else View.GONE
        detail.tvPurchaseDetailReceived.text = data.receivedAt.takeIf { it.isNotBlank() }?.let { "Mercadería recibida el ${formatDate(it)} · Stock actualizado" } ?: "Mercadería recibida · Stock actualizado"

        val pending = purchase.status == "pending"
        detail.btnPurchaseDetailReceive.visibility = if (pending) View.VISIBLE else View.GONE
        detail.btnPurchaseDetailCancel.visibility = if (pending) View.VISIBLE else View.GONE
        detail.btnPurchaseDetailReceive.setOnClickListener { confirmPurchaseAction(purchase, "receive", dialog) }
        detail.btnPurchaseDetailCancel.setOnClickListener { confirmPurchaseAction(purchase, "cancel", dialog) }
        detail.btnClosePurchaseDetail.setOnClickListener { dialog.dismiss() }
    }

    private fun confirmPurchaseAction(purchase: Purchase, action: String, dialog: BottomSheetDialog) {
        val receiving = action == "receive"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (receiving) "Recibir mercadería" else "Cancelar compra")
            .setMessage(if (receiving) "Al confirmar se sumará la cantidad al stock y se actualizará el costo promedio." else "La compra quedará cancelada y no afectará el inventario.")
            .setNegativeButton(if (receiving) "Volver" else "No", null)
            .setPositiveButton(if (receiving) "Confirmar recepción" else "Cancelar compra") { _, _ ->
                thread {
                    val ok = runCatching { if (receiving) repository.receivePurchase(purchase.id) else repository.cancelPurchase(purchase.id) }.getOrDefault(false)
                    runOnUiThread {
                        Snackbar.make(binding.root, if (ok) {
                            if (receiving) "Compra recibida. Stock actualizado." else "Compra cancelada."
                        } else "No se pudo actualizar la compra.", Snackbar.LENGTH_LONG).show()
                        if (ok) {
                            dialog.dismiss()
                            loadPurchases()
                        }
                    }
                }
            }
            .show()
    }

    private fun statusLabel(value: String) = when (value) { "received" -> "RECIBIDA"; "cancelled" -> "CANCELADA"; else -> "PENDIENTE" }
    private fun formatDate(value: String): String = runCatching {
        val normalized = value.substringBefore('T').ifBlank { value }
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(normalized) ?: java.util.Date())
    }.getOrDefault(value)
    private fun formatQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else money(value)
    private fun money(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun visiblePurchaseNotes(value: String, supplier: String): String = value.split(" · ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it != supplier.trim() && !it.startsWith("RUC:") && !it.startsWith("[purchase_discount=") }
        .joinToString(" · ")

    private fun openTopLevel(destination: Class<out AppCompatActivity>): Boolean {
        startActivity(Intent(this, destination))
        applyNavigationTransition()
        finish()
        return true
    }
}

package com.tecnoselva.factura.features.purchases.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.ui.applySystemBarInsets
import com.tecnoselva.factura.databinding.DialogPurchaseDetailBinding
import com.tecnoselva.factura.databinding.ItemPurchaseDetailLineBinding
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.purchases.data.LocalPurchasesRepository
import com.tecnoselva.factura.features.purchases.data.PurchaseDataSource
import com.tecnoselva.factura.features.purchases.data.PurchasesRepository
import com.tecnoselva.factura.features.purchases.model.Purchase
import com.tecnoselva.factura.features.purchases.model.PurchaseDetail
import kotlin.concurrent.thread

class PurchaseDetailActivity : AppCompatActivity() {
    private lateinit var binding: DialogPurchaseDetailBinding
    private lateinit var repository: PurchaseDataSource
    private lateinit var initialPurchase: Purchase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPurchaseDetailBinding.inflate(layoutInflater)
        binding.root.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        val session = SessionManager(this)
        repository = if (session.isLoggedIn && session.storeId.isNotBlank()) {
            PurchasesRepository(SessionRepository(session), this)
        } else {
            LocalPurchasesRepository(this, LocalStoreProfileRepository(this).localStoreId)
        }
        initialPurchase = Purchase(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            purchaseDate = intent.getStringExtra(EXTRA_DATE).orEmpty(),
            supplierName = intent.getStringExtra(EXTRA_SUPPLIER).orEmpty(),
            invoiceNumber = intent.getStringExtra(EXTRA_INVOICE).orEmpty(),
            total = intent.getDoubleExtra(EXTRA_TOTAL, 0.0),
            status = intent.getStringExtra(EXTRA_STATUS).orEmpty(),
            notes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        )
        val preloadedDetail = intent.getStringExtra(EXTRA_DETAIL_JSON)?.let {
            runCatching { Gson().fromJson(it, PurchaseDetail::class.java) }.getOrNull()
        }
        val detailToShow = preloadedDetail?.let { detail ->
            val supplier = detail.purchase.supplierName
                .takeUnless { it.isBlank() || it.equals("Sin proveedor", ignoreCase = true) }
                ?: initialPurchase.supplierName
            detail.copy(purchase = detail.purchase.copy(supplierName = supplier))
        } ?: PurchaseDetail(initialPurchase)
        bindPurchaseDetail(detailToShow)
        binding.btnClosePurchaseDetail.setOnClickListener { finish() }
        if (preloadedDetail == null) loadDetail()
    }

    private fun loadDetail() {
        thread {
            val result = runCatching { repository.getPurchaseDetail(initialPurchase.id) }
            runOnUiThread {
                result.onSuccess { loaded ->
                    if (loaded != null) {
                        val loadedPurchase = loaded.purchase
                        val supplier = loadedPurchase.supplierName
                            .takeUnless { it.isBlank() || it.equals("Sin proveedor", ignoreCase = true) }
                            ?: initialPurchase.supplierName
                        bindPurchaseDetail(loaded.copy(purchase = loadedPurchase.copy(supplierName = supplier)))
                    }
                }.onFailure {
                    Snackbar.make(binding.root, "No se pudo cargar el detalle completo.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindPurchaseDetail(data: PurchaseDetail) {
        val purchase = data.purchase
        binding.tvPurchaseDetailSupplier.text = purchase.supplierName.ifBlank { "Sin proveedor" }
        binding.tvPurchaseDetailStatus.text = statusLabel(purchase.status)
        binding.tvPurchaseDetailDocument.text = purchase.invoiceNumber.ifBlank { "Compra sin documento" }
        binding.tvPurchaseDetailDate.text = formatDate(purchase.purchaseDate)
        binding.tvPurchaseDetailRuc.text = data.supplierRuc.ifBlank { "No registrado" }
        binding.tvPurchaseDetailSupplierMeta.text = listOf(
            purchase.supplierName.ifBlank { "Proveedor no registrado" },
            purchase.invoiceNumber.takeIf { it.isNotBlank() }?.let { "Documento $it" }
        ).filterNotNull().joinToString(" · ")

        binding.purchaseDetailItems.removeAllViews()
        binding.tvPurchaseDetailItemsEmpty.visibility = if (data.items.isEmpty()) View.VISIBLE else View.GONE
        data.items.forEach { item ->
            val line = ItemPurchaseDetailLineBinding.inflate(layoutInflater, binding.purchaseDetailItems, false)
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
            binding.purchaseDetailItems.addView(line.root)
        }

        val gross = data.items.sumOf { it.totalCost }.takeIf { it > 0.0 } ?: (purchase.total + data.discount)
        val discount = data.discount.coerceIn(0.0, gross)
        val subtotal = data.subtotal.takeIf { it > 0.0 } ?: (purchase.total / 1.18)
        val tax = data.tax.takeIf { it > 0.0 } ?: (purchase.total - subtotal)
        binding.tvPurchaseDetailTotal.text = "S/ ${money(purchase.total)}"
        binding.tvPurchaseDetailTotalsBreakdown.text = listOf(
            "Productos S/ ${money(gross)}",
            discount.takeIf { it > 0.0 }?.let { "Descuento S/ ${money(it)}" },
            "Subtotal S/ ${money(subtotal)}",
            "IGV S/ ${money(tax)}"
        ).filterNotNull().joinToString("  ·  ")

        val notes = visiblePurchaseNotes(purchase.notes, purchase.supplierName)
        binding.cardPurchaseDetailNotes.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
        binding.tvPurchaseDetailNotes.text = notes
        binding.cardPurchaseDetailReceived.visibility = if (purchase.status == "received") View.VISIBLE else View.GONE
        binding.tvPurchaseDetailReceived.text = data.receivedAt.takeIf { it.isNotBlank() }?.let { "Mercadería recibida el ${formatDate(it)} · Stock actualizado" } ?: "Mercadería recibida · Stock actualizado"

        val pending = purchase.status == "pending"
        binding.btnPurchaseDetailReceive.visibility = if (pending) View.VISIBLE else View.GONE
        binding.btnPurchaseDetailCancel.visibility = if (pending) View.VISIBLE else View.GONE
        binding.btnPurchaseDetailReceive.setOnClickListener { confirmPurchaseAction(purchase, "receive") }
        binding.btnPurchaseDetailCancel.setOnClickListener { confirmPurchaseAction(purchase, "cancel") }
    }

    private fun confirmPurchaseAction(purchase: Purchase, action: String) {
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
                        if (ok) finish()
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

    companion object {
        const val EXTRA_ID = "purchase_id"
        const val EXTRA_DATE = "purchase_date"
        const val EXTRA_SUPPLIER = "purchase_supplier"
        const val EXTRA_INVOICE = "purchase_invoice"
        const val EXTRA_TOTAL = "purchase_total"
        const val EXTRA_STATUS = "purchase_status"
        const val EXTRA_NOTES = "purchase_notes"
        const val EXTRA_DETAIL_JSON = "purchase_detail_json"
    }
}

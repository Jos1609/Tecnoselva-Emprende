package com.tecnoselva.factura.features.purchases.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.databinding.ActivityPurchaseWizardBinding
import com.tecnoselva.factura.databinding.DialogPurchaseProductPickerBinding
import com.tecnoselva.factura.databinding.ItemPurchaseProductPickerBinding
import com.tecnoselva.factura.databinding.ItemPurchaseDraftBinding
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.purchases.data.LocalPurchasesRepository
import com.tecnoselva.factura.features.purchases.data.PurchaseDataSource
import com.tecnoselva.factura.features.purchases.data.PurchasesRepository
import com.tecnoselva.factura.features.purchases.model.PurchaseDraftItem
import com.tecnoselva.factura.features.purchases.model.PurchaseProduct
import com.tecnoselva.factura.features.purchases.model.PurchaseVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class PurchaseWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPurchaseWizardBinding
    private lateinit var repository: PurchaseDataSource
    private val drafts = mutableListOf<PurchaseDraftItem>()
    private var products: List<PurchaseProduct> = emptyList()
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchaseWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val session = SessionManager(this)
        repository = if (session.isLoggedIn && session.storeId.isNotBlank()) {
            PurchasesRepository(SessionRepository(session), this)
        } else {
            LocalPurchasesRepository(this, LocalStoreProfileRepository(this).localStoreId)
        }
        binding.etPurchaseDate.setText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        binding.btnPurchasePrevious.setOnClickListener { if (step == 0) finish() else { step--; renderStep() } }
        binding.btnPurchaseNext.setOnClickListener { nextStep() }
        binding.btnPurchaseSave.setOnClickListener { savePurchase() }
        binding.btnAddPurchaseItem.setOnClickListener { showProductPicker() }
        renderStep()
    }

    private fun nextStep() {
        val valid = when (step) {
            0 -> validateSupplier()
            1 -> validateProducts()
            else -> false
        }
        if (!valid) return
        if (step < 2) { step++; renderStep() }
    }

    private fun validateSupplier(): Boolean {
        val value = binding.etPurchaseSupplier.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) { binding.etPurchaseSupplier.error = "Ingresa el nombre del proveedor"; return false }
        return true
    }

    private fun validateProducts(): Boolean {
        if (drafts.isEmpty()) { Snackbar.make(binding.root, "Agrega al menos un producto.", Snackbar.LENGTH_LONG).show(); return false }
        return true
    }

    private fun showProductPicker() {
        val picker = DialogPurchaseProductPickerBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar producto")
            .setView(picker.root)
            .setNegativeButton("Cancelar", null)
            .create()
        fun renderProducts(results: List<PurchaseProduct>) {
            picker.purchaseProductResults.removeAllViews()
            picker.tvPurchaseProductEmpty.text = if (results.isEmpty()) "No se encontraron productos." else ""
            picker.tvPurchaseProductEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            results.forEach { product ->
                val row = ItemPurchaseProductPickerBinding.inflate(layoutInflater, picker.purchaseProductResults, false)
                row.tvPickerProductName.text = product.name
                row.tvPickerProductMeta.text = "SKU: ${product.sku.ifBlank { "N/A" }} · Stock actual: ${formatQuantity(product.stock)}${if (product.hasVariants) " · Con variantes" else ""}"
                row.tvPickerProductCost.text = if (product.costPrice > 0) "Costo actual: S/ ${money(product.costPrice)}" else "Costo actual no definido"
                row.btnPickerSelectProduct.setOnClickListener {
                    dialog.dismiss()
                    if (product.hasVariants) loadVariantsAndChoose(product) else showPurchaseItemDialog(product)
                }
                picker.purchaseProductResults.addView(row.root)
            }
        }
        val searchHandler = Handler(Looper.getMainLooper())
        var searchRequest = 0
        picker.etPurchaseProductSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                searchRequest += 1
                val requestId = searchRequest
                if (query.length < 2) {
                    picker.purchaseProductResults.removeAllViews()
                    picker.tvPurchaseProductEmpty.text = "Escribe al menos 2 caracteres para buscar."
                    picker.tvPurchaseProductEmpty.visibility = View.VISIBLE
                    return
                }
                picker.tvPurchaseProductEmpty.text = "Buscando…"
                picker.tvPurchaseProductEmpty.visibility = View.VISIBLE
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    thread {
                        val results = runCatching { repository.searchProducts(query) }.getOrDefault(emptyList())
                        runOnUiThread { if (requestId == searchRequest && dialog.isShowing) { products = results; renderProducts(results) } }
                    }
                }, 300)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        dialog.show()
        picker.tvPurchaseProductEmpty.text = "Escribe al menos 2 caracteres para buscar."
        picker.tvPurchaseProductEmpty.visibility = View.VISIBLE
    }

    private fun loadVariantsAndChoose(product: PurchaseProduct) {
        Snackbar.make(binding.root, "Cargando variantes…", Snackbar.LENGTH_SHORT).show()
        thread {
            val variants = runCatching { repository.searchVariants(product.id) }.getOrDefault(emptyList())
            runOnUiThread {
                if (variants.isEmpty()) {
                    Snackbar.make(binding.root, "No se encontraron variantes para este producto.", Snackbar.LENGTH_LONG).show()
                } else {
                    showVariantPicker(product, variants)
                }
            }
        }
    }

    private fun showVariantPicker(product: PurchaseProduct, variants: List<PurchaseVariant>) {
        val picker = com.tecnoselva.factura.databinding.DialogPurchaseVariantPickerBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar variante")
            .setView(picker.root)
            .setNegativeButton("Cancelar", null)
            .create()
        picker.tvPurchaseVariantProduct.text = product.name
        picker.purchaseVariantResults.removeAllViews()
        variants.forEach { variant ->
            val row = com.tecnoselva.factura.databinding.ItemPurchaseVariantPickerBinding.inflate(layoutInflater, picker.purchaseVariantResults, false)
            row.tvPickerVariantName.text = variant.name
            row.tvPickerVariantMeta.text = "SKU: ${variant.sku.ifBlank { product.sku.ifBlank { "N/A" } }} · Stock actual: ${formatQuantity(variant.stock)}"
            row.tvPickerVariantCost.text = if (variant.costPrice > 0) "Costo actual: S/ ${money(variant.costPrice)}" else "Costo actual no definido"
            row.btnPickerSelectVariant.setOnClickListener {
                dialog.dismiss()
                showPurchaseItemDialog(product, variant)
            }
            picker.purchaseVariantResults.addView(row.root)
        }
        dialog.show()
    }

    private fun showPurchaseItemDialog(product: PurchaseProduct, variant: PurchaseVariant? = null, current: PurchaseDraftItem? = null, position: Int? = null) {
        val draftKey = { item: PurchaseDraftItem -> "${item.productId}:${item.variantId.orEmpty()}" }
        val selectedKey = "${product.id}:${variant?.id.orEmpty()}"
        if (position == null && drafts.any { draftKey(it) == selectedKey }) { Snackbar.make(binding.root, "Ese producto ya fue agregado.", Snackbar.LENGTH_SHORT).show(); return }
        val dialogView = layoutInflater.inflate(R.layout.dialog_invoice_item, null)
        val productInput = dialogView.findViewById<EditText>(R.id.dialogEtDescription)
        val quantityInput = dialogView.findViewById<EditText>(R.id.dialogEtQuantity)
        val costInput = dialogView.findViewById<EditText>(R.id.dialogEtUnitPrice)
        val modeButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnItemType)
        productInput.setText(if (variant == null) product.name else "${product.name} · ${variant.name}")
        productInput.isEnabled = false
        quantityInput.setText(formatEditableNumber(current?.quantity ?: 1.0))
        val initialCost = current?.unitCost ?: variant?.costPrice ?: product.costPrice
        if (initialCost > 0.0) costInput.setText(formatEditableNumber(initialCost))
        modeButton.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (position == null) "Agregar producto" else "Editar producto").setView(dialogView).setNegativeButton("Cancelar", null).setPositiveButton(if (position == null) "Agregar" else "Guardar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val quantity = quantityInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                val cost = costInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: -1.0
                when {
                    quantity <= 0 -> quantityInput.error = "Ingresa una cantidad mayor a cero"
                    cost < 0 -> costInput.error = "Ingresa un costo válido"
                    else -> {
                        val saved = PurchaseDraftItem(
                            productId = product.id,
                            productName = product.name,
                            sku = variant?.sku?.ifBlank { product.sku } ?: product.sku,
                            quantity = quantity,
                            unitCost = cost,
                            variantId = variant?.id ?: current?.variantId,
                            variantLabel = variant?.name ?: current?.variantLabel.orEmpty(),
                            stock = variant?.stock ?: product.stock
                        )
                        if (position == null) drafts.add(saved) else drafts[position] = saved
                        renderDraftItems(); dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun renderDraftItems() {
        binding.purchaseItemsContainer.removeAllViews()
        drafts.forEachIndexed { index, item ->
            val row = ItemPurchaseDraftBinding.inflate(layoutInflater, binding.purchaseItemsContainer, false)
            row.tvDraftProductName.text = item.productName
            row.tvDraftProductMeta.text = buildString {
                append("SKU: ${item.sku.ifBlank { "N/A" }} · Stock actual: ${formatQuantity(item.stock)}")
                if (item.variantLabel.isNotBlank()) append(" · ${item.variantLabel}")
            }
            row.tvDraftQuantity.text = "Cantidad: ${formatQuantity(item.quantity)}"
            row.tvDraftCost.text = "Costo unitario: S/ ${money(item.unitCost)}"
            row.tvDraftSubtotal.text = "S/ ${money(item.quantity * item.unitCost)}"
            val variant = item.variantId?.let { PurchaseVariant(it, item.productId, item.variantLabel.ifBlank { "Variante" }, item.sku, item.unitCost) }
            row.btnEditDraft.setOnClickListener {
                showPurchaseItemDialog(PurchaseProduct(item.productId, item.productName, item.sku, item.unitCost, item.stock), variant, item, index)
            }
            row.btnRemoveDraft.setOnClickListener { drafts.removeAt(index); renderDraftItems() }
            binding.purchaseItemsContainer.addView(row.root)
        }
        binding.tvPurchaseItemsCount.text = if (drafts.size == 1) "1 ítem agregado" else "${drafts.size} ítems agregados"
        updateTotals()
    }

    private fun updateTotals() {
        val gross = drafts.sumOf { it.quantity * it.unitCost }
        val discount = binding.etPurchaseDiscount.text?.toString()?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.0, gross) ?: 0.0
        val total = gross - discount
        val subtotal = total / 1.18
        val tax = total - subtotal
        val summary = "Productos: S/ ${money(gross)}  ·  Descuento: S/ ${money(discount)}\nSubtotal: S/ ${money(subtotal)}  ·  IGV (18%): S/ ${money(tax)}\nTotal compra: S/ ${money(total)}"
        binding.tvPurchaseWizardTotal.text = summary
        binding.tvPurchaseBottomTotal.text = "S/ ${money(total)}"
        binding.tvPurchaseReviewTotals.text = summary
    }

    private fun renderReview() {
        binding.tvPurchaseReviewSupplier.text = binding.etPurchaseSupplier.text?.toString()?.trim().orEmpty()
        binding.tvPurchaseReviewDocument.text = listOf(
            binding.etPurchaseRuc.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { "RUC $it" },
            binding.etPurchaseInvoice.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { "Documento $it" },
            binding.etPurchaseDate.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ").ifBlank { "Sin documento · Fecha no indicada" }
        binding.purchaseReviewItems.removeAllViews()
        drafts.forEach { item ->
            binding.purchaseReviewItems.addView(TextView(this).apply {
                text = "${formatQuantity(item.quantity)} × ${item.productName}${item.variantLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}  ·  S/ ${money(item.quantity * item.unitCost)}"
                setTextColor(ContextCompat.getColor(this@PurchaseWizardActivity, R.color.brand_text_secondary))
                setPadding(0, 4, 0, 4)
            })
        }
        updateTotals()
    }

    private fun renderStep() {
        binding.panelSupplier.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.panelProducts.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.panelReview.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.btnPurchasePrevious.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        binding.btnPurchaseNext.visibility = if (step < 2) View.VISIBLE else View.GONE
        binding.btnPurchaseSave.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.purchaseBottomTotal.visibility = if (step == 1) View.VISIBLE else View.INVISIBLE
        if (step == 1) renderDraftItems()
        if (step == 2) renderReview()
        styleIndicator(binding.stepSupplierIndicator, 0)
        styleIndicator(binding.stepProductsIndicator, 1)
        styleIndicator(binding.stepReviewIndicator, 2)
    }

    private fun styleIndicator(view: TextView, target: Int) {
        val reached = target <= step
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (reached) R.color.brand_navy else R.color.brand_surface_subtle))
        view.setTextColor(ContextCompat.getColor(this, if (reached) R.color.white else R.color.brand_text_secondary))
        view.text = if (target < step) "✓" else (target + 1).toString()
    }

    private fun savePurchase() {
        if (!validateSupplier() || !validateProducts()) return
        val discount = binding.etPurchaseDiscount.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val gross = drafts.sumOf { it.quantity * it.unitCost }
        if (discount > gross) { binding.etPurchaseDiscount.error = "No puede superar el total de productos"; return }
        binding.btnPurchaseSave.isEnabled = false
        thread {
            val ok = runCatching {
                repository.createPurchase(
                    drafts,
                    binding.etPurchaseInvoice.text?.toString().orEmpty(),
                    binding.etPurchaseSupplier.text?.toString().orEmpty(),
                    binding.etPurchaseRuc.text?.toString().orEmpty(),
                    binding.etPurchaseDate.text?.toString().orEmpty(),
                    discount,
                    binding.etPurchaseNotes.text?.toString().orEmpty()
                )
            }.getOrDefault(false)
            runOnUiThread {
                binding.btnPurchaseSave.isEnabled = true
                if (ok) { setResult(RESULT_OK); finish() } else Snackbar.make(binding.root, "No se pudo registrar la compra.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() { if (step > 0) { step--; renderStep() } else super.onBackPressed() }
    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun formatQuantity(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else money(value)
    private fun formatEditableNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else money(value)
}

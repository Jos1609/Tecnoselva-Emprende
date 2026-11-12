package com.tecnoselva.factura.features.pos.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.databinding.ActivityPosBinding
import com.tecnoselva.factura.features.auth.data.SessionExpiredException
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.auth.ui.LoginActivity
import com.tecnoselva.factura.features.billing.data.BillingRepository
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity
import com.tecnoselva.factura.features.pos.data.PosRepository
import com.tecnoselva.factura.features.pos.data.PosCatalogCache
import com.tecnoselva.factura.features.pos.data.LocalPosRepository
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.pos.model.*
import com.tecnoselva.factura.features.pos.ui.components.PosCartAdapter
import com.tecnoselva.factura.features.pos.ui.components.PosCatalogAdapter
import java.util.Locale
import java.util.concurrent.Executors

class PosActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPosBinding
    private lateinit var session: SessionManager
    private lateinit var repository: PosRepository
    private var localRepository: LocalPosRepository? = null
    private lateinit var billingRepository: BillingRepository
    private lateinit var catalogCache: PosCatalogCache
    private lateinit var catalogAdapter: PosCatalogAdapter
    private lateinit var cartAdapter: PosCartAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val catalog = mutableListOf<PosCatalogItem>()
    private val cart = mutableListOf<PosCartItem>()
    private var query = ""
    private var filter = CatalogFilter.ALL
    private var checkoutVisible = false
    private var saving = false
    private var hasResumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        val sessionRepository = SessionRepository(session)
        repository = PosRepository(sessionRepository, session.currentUserId)
        if (!session.isLoggedIn) {
            localRepository = LocalPosRepository(this, LocalStoreProfileRepository(this).localStoreId)
        }
        billingRepository = BillingRepository(sessionRepository)
        catalogCache = PosCatalogCache(this)
        binding = ActivityPosBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.SALES)
        setupUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (checkoutVisible) showCatalog() else finish()
            }
        })
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce && ::binding.isInitialized && session.isLoggedIn) loadCatalog(forceRefresh = true)
        hasResumedOnce = true
    }

    private fun setupUi() = with(binding) {
        bottomNavigationPos.selectedItemId = R.id.nav_sales
        bottomNavigationPos.menu.findItem(R.id.nav_billing).isVisible = SessionManager(this@PosActivity).isLoggedIn
        bottomNavigationPos.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { openTopLevel(HomeActivity::class.java); true }
                R.id.nav_sales -> true
                R.id.nav_inventory -> { openTopLevel(LocalCatalogActivity::class.java); true }
                R.id.nav_billing -> { openTopLevel(DocumentsActivity::class.java); true }
                else -> false
            }
        }

        catalogAdapter = PosCatalogAdapter(::addCatalogItem)
        rvPosCatalog.layoutManager = LinearLayoutManager(this@PosActivity)
        rvPosCatalog.adapter = catalogAdapter
        cartAdapter = PosCartAdapter(::changeQuantity, ::removeCartItem)
        rvPosCart.layoutManager = LinearLayoutManager(this@PosActivity)
        rvPosCart.adapter = cartAdapter
        swipeRefreshPos.setOnRefreshListener { loadCatalog(forceRefresh = true) }

        groupPosFilter.check(R.id.posFilterAll)
        groupPosFilter.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.posFilterProducts -> CatalogFilter.PRODUCTS
                R.id.posFilterServices -> CatalogFilter.SERVICES
                else -> CatalogFilter.ALL
            }
            renderCatalog()
        }
        etPosSearch.addTextChangedListener(simpleWatcher { value -> query = value; renderCatalog() })

        spinnerPosDocumentType.adapter = spinnerAdapter(
            if (session.isLoggedIn) PosDocumentType.entries.map { it.label }
            else listOf(PosDocumentType.SALE_NOTE.label)
        )
        spinnerPosCustomerDocType.adapter = spinnerAdapter(PosCustomerDocumentType.entries.map { it.label })
        spinnerPosPaymentMode.adapter = spinnerAdapter(PosPaymentMode.entries.map { it.label })
        spinnerPosPaymentMethod.adapter = spinnerAdapter(PosPaymentMethod.entries.map { it.label })
        spinnerPosDocumentType.onItemSelectedListener = selectionListener { position -> configureDocument(PosDocumentType.entries[position]) }
        spinnerPosCustomerDocType.onItemSelectedListener = selectionListener { position -> configureCustomerDocument(PosCustomerDocumentType.entries[position]) }
        spinnerPosPaymentMode.onItemSelectedListener = selectionListener { updatePaymentControls() }
        switchPosIgv.setOnCheckedChangeListener { _, _ -> updateTotals() }
        etPosCustomerDocument.setText("00000000")
        etPosCustomerName.setText("Consumidor Final")
        if (session.isLoggedIn) etPosCustomerDocument.addTextChangedListener(simpleWatcher { lookupCustomerIfReady(it) })
        btnOpenPosCart.setOnClickListener { if (cart.isNotEmpty()) showCheckout() }
        btnBackToPosCatalog.setOnClickListener { showCatalog() }
        btnCompletePosSale.setOnClickListener { completeSale() }
        updateCartUi()
    }

    private fun openTopLevel(destination: Class<out AppCompatActivity>) {
        startActivity(Intent(this, destination).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        applyNavigationTransition()
        finish()
    }



    private fun loadCatalog(forceRefresh: Boolean = false) {
        if (!session.isLoggedIn) {
            binding.progressPos.visibility = if (catalog.isEmpty()) View.VISIBLE else View.GONE
            executor.execute {
                try {
                    val items = localRepository?.loadCatalog().orEmpty()
                    runOnUiThread {
                        catalog.clear(); catalog.addAll(items)
                        binding.progressPos.visibility = View.GONE
                        binding.swipeRefreshPos.isRefreshing = false
                        renderCatalog()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        binding.progressPos.visibility = View.GONE
                        binding.swipeRefreshPos.isRefreshing = false
                        binding.tvPosMessage.text = error.localizedMessage ?: "No se pudo cargar el catálogo local"
                        binding.tvPosMessage.visibility = View.VISIBLE
                    }
                }
            }
            return
        }
        if (!forceRefresh) {
            catalogCache.load(session.storeId)?.let { cached ->
                catalog.clear()
                catalog.addAll(cached.items)
                renderCatalog()
                if (cached.isFresh) return
            }
        }
        binding.progressPos.visibility = if (catalog.isEmpty()) View.VISIBLE else View.GONE
        binding.tvPosMessage.visibility = View.GONE
        executor.execute {
            try {
                val items = repository.loadCatalog()
                runOnUiThread {
                    catalog.clear(); catalog.addAll(items)
                    catalogCache.save(session.storeId, catalog)
                    binding.progressPos.visibility = View.GONE
                    binding.swipeRefreshPos.isRefreshing = false
                    renderCatalog()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    binding.progressPos.visibility = View.GONE
                    binding.swipeRefreshPos.isRefreshing = false
                    if (error is SessionExpiredException) redirectToLogin() else {
                        Log.e("PosActivity", "No se pudo cargar el catálogo", error)
                        if (catalog.isEmpty()) {
                            binding.tvPosMessage.text = "No se pudo cargar el catálogo. Revisa tu conexión y desliza para reintentar."
                            binding.tvPosMessage.visibility = View.VISIBLE
                        } else {
                            // Si existe caché, se conserva el catálogo sin interrumpir la venta.
                            binding.tvPosMessage.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun renderCatalog() {
        val normalized = query.trim().lowercase()
        val visible = catalog.filter { item ->
            val typeMatches = filter == CatalogFilter.ALL || filter == CatalogFilter.PRODUCTS && item.type == PosCatalogType.PRODUCT || filter == CatalogFilter.SERVICES && item.type == PosCatalogType.SERVICE
            val searchMatches = normalized.isBlank() || listOf(item.name, item.sku, item.category).any { it.lowercase().contains(normalized) }
            typeMatches && searchMatches
        }
        catalogAdapter.submitList(visible)
        binding.tvPosMessage.text = if (visible.isEmpty()) "No hay productos o servicios que coincidan." else ""
        binding.tvPosMessage.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addCatalogItem(item: PosCatalogItem) {
        if (!item.available) { Snackbar.make(binding.root, "${item.name} no tiene stock disponible", Snackbar.LENGTH_SHORT).show(); return }
        if (item.hasVariants) { loadAndChooseVariant(item); return }
        addToCart(item, null)
    }

    private fun loadAndChooseVariant(item: PosCatalogItem) {
        Snackbar.make(binding.root, "Cargando variantes…", Snackbar.LENGTH_SHORT).show()
        executor.execute {
            try {
                val variants = (if (session.isLoggedIn) repository.loadVariants(item.id) else localRepository?.loadVariants(item.id).orEmpty())
                    .filter { it.stock > 0 }
                runOnUiThread {
                    if (variants.isEmpty()) { Snackbar.make(binding.root, "No hay variantes disponibles", Snackbar.LENGTH_LONG).show(); return@runOnUiThread }
                    showVariantDialog(item, variants)
                }
            } catch (error: Exception) { runOnUiThread { Snackbar.make(binding.root, error.localizedMessage ?: "No se cargaron las variantes", Snackbar.LENGTH_LONG).show() } }
        }
    }

    private fun showVariantDialog(item: PosCatalogItem, variants: List<PosVariant>) {
        val content = layoutInflater.inflate(R.layout.dialog_pos_variants, null)
        content.findViewById<TextView>(R.id.tvVariantProductName).text = item.name
        content.findViewById<TextView>(R.id.tvVariantCount).text =
            "${variants.size} ${if (variants.size == 1) "variante disponible" else "variantes disponibles"}"
        val options = content.findViewById<android.widget.LinearLayout>(R.id.variantOptionsContainer)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setNegativeButton("Cerrar", null)
            .create()

        variants.forEach { variant ->
            val option = layoutInflater.inflate(R.layout.item_pos_variant, options, false)
            option.findViewById<TextView>(R.id.tvVariantLabel).text = variant.label
            val sku = variant.sku.takeIf { it.isNotBlank() }?.let { "SKU $it  •  " }.orEmpty()
            option.findViewById<TextView>(R.id.tvVariantSkuStock).text =
                "$sku${formatStock(variant.stock)} disponibles"
            option.findViewById<TextView>(R.id.tvVariantPrice).text = "S/ ${money(variant.price)}"
            option.setOnClickListener {
                addToCart(item, variant)
                dialog.dismiss()
            }
            options.addView(option)
        }
        dialog.show()
    }

    private fun formatStock(stock: Double): String {
        return if (stock % 1.0 == 0.0) stock.toInt().toString() else String.format(Locale.US, "%.2f", stock)
    }

    private fun addToCart(item: PosCatalogItem, variant: PosVariant?) {
        val key = "${item.type}:${item.id}:${variant?.id.orEmpty()}"
        val index = cart.indexOfFirst { it.key == key }
        val maxStock = variant?.stock ?: item.stock
        if (index >= 0) {
            val current = cart[index]
            if (current.trackInventory && current.quantity + 1 > (maxStock ?: 0.0)) { Snackbar.make(binding.root, "Stock máximo alcanzado", Snackbar.LENGTH_SHORT).show(); return }
            cart[index] = current.copy(quantity = current.quantity + 1)
        } else {
            cart += PosCartItem(key, item.id, item.type, item.name, variant?.sku ?: item.sku, variant?.price ?: item.price, 1, maxStock, item.trackInventory, variant?.id, variant?.label)
        }
        updateCartUi()
        Snackbar.make(binding.root, "${item.name} agregado", Snackbar.LENGTH_SHORT).show()
    }

    private fun changeQuantity(item: PosCartItem, delta: Int) {
        val index = cart.indexOfFirst { it.key == item.key }; if (index < 0) return
        val next = item.quantity + delta
        if (next <= 0) cart.removeAt(index)
        else if (item.trackInventory && next > (item.stock ?: 0.0)) Snackbar.make(binding.root, "No hay más stock disponible", Snackbar.LENGTH_SHORT).show()
        else cart[index] = item.copy(quantity = next)
        updateCartUi()
    }

    private fun removeCartItem(item: PosCartItem) { cart.removeAll { it.key == item.key }; updateCartUi() }

    private fun updateCartUi() {
        val count = cart.sumOf { it.quantity }; val total = cart.sumOf { it.subtotal }
        binding.tvPosCartSummary.text = if (count == 0) "Carrito vacío" else "$count ${if (count == 1) "ítem" else "ítems"} en la venta"
        binding.tvPosCartTotal.text = "S/ ${money(total)}"
        binding.btnOpenPosCart.isEnabled = cart.isNotEmpty()
        cartAdapter.submitList(cart.toList())
        if (checkoutVisible && cart.isEmpty()) showCatalog()
        updateTotals()
    }

    private fun showCheckout() {
        checkoutVisible = true
        binding.catalogContent.visibility = View.GONE
        binding.checkoutContent.visibility = View.VISIBLE
        binding.cartBar.visibility = View.GONE
        updateCartUi()
    }

    private fun showCatalog() {
        checkoutVisible = false
        binding.catalogContent.visibility = View.VISIBLE
        binding.checkoutContent.visibility = View.GONE
        binding.cartBar.visibility = View.VISIBLE
    }

    private fun configureDocument(type: PosDocumentType) {
        if (type == PosDocumentType.INVOICE) {
            binding.spinnerPosCustomerDocType.setSelection(PosCustomerDocumentType.RUC.ordinal)
            if (binding.etPosCustomerDocument.text?.toString() == "00000000") binding.etPosCustomerDocument.setText("")
            if (binding.etPosCustomerName.text?.toString() == "Consumidor Final") binding.etPosCustomerName.setText("")
        }
    }

    private fun configureCustomerDocument(type: PosCustomerDocumentType) {
        binding.etPosCustomerDocument.inputType = if (type == PosCustomerDocumentType.DNI || type == PosCustomerDocumentType.RUC) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun lookupCustomerIfReady(value: String) {
        val type = PosCustomerDocumentType.entries[binding.spinnerPosCustomerDocType.selectedItemPosition.coerceAtLeast(0)]
        val expected = if (type == PosCustomerDocumentType.RUC) 11 else if (type == PosCustomerDocumentType.DNI) 8 else return
        if (value.trim().length != expected || value == "00000000") return
        billingRepository.lookupCustomer(if (type == PosCustomerDocumentType.RUC) "6" else "1", value.trim()) { result ->
            runOnUiThread { if (result.success && !result.name.isNullOrBlank()) binding.etPosCustomerName.setText(result.name) }
        }
    }

    private fun updatePaymentControls() {
        val mode = PosPaymentMode.entries[binding.spinnerPosPaymentMode.selectedItemPosition.coerceAtLeast(0)]
        binding.layoutPosAmountPaid.visibility = if (mode == PosPaymentMode.PARTIAL) View.VISIBLE else View.GONE
        binding.spinnerPosPaymentMethod.visibility = if (mode == PosPaymentMode.CREDIT) View.GONE else View.VISIBLE
    }

    private fun updateTotals() {
        if (!::binding.isInitialized) return
        val total = cart.sumOf { it.subtotal }
        val subtotal = if (binding.switchPosIgv.isChecked) total / 1.18 else total
        val tax = total - subtotal
        binding.tvPosSubtotal.text = "Subtotal: S/ ${money(subtotal)}"
        binding.tvPosTax.text = if (binding.switchPosIgv.isChecked) "IGV (18%): S/ ${money(tax)}" else "Operación exonerada · IGV: S/ 0.00"
        binding.tvPosCheckoutTotal.text = "TOTAL  S/ ${money(total)}"
    }

    private fun completeSale() {
        if (saving || cart.isEmpty()) return
        val documentType = PosDocumentType.entries[binding.spinnerPosDocumentType.selectedItemPosition]
        val customerDocType = PosCustomerDocumentType.entries[binding.spinnerPosCustomerDocType.selectedItemPosition]
        val document = binding.etPosCustomerDocument.text?.toString()?.trim().orEmpty()
        val customer = binding.etPosCustomerName.text?.toString()?.trim().orEmpty()
        if (customer.isBlank()) { binding.etPosCustomerName.error = "Ingresa el cliente"; return }
        if (documentType == PosDocumentType.INVOICE && (customerDocType != PosCustomerDocumentType.RUC || document.length != 11)) { Snackbar.make(binding.root, "La factura requiere un RUC válido de 11 dígitos", Snackbar.LENGTH_LONG).show(); return }
        if (customerDocType == PosCustomerDocumentType.DNI && document.length != 8) { Snackbar.make(binding.root, "El DNI debe tener 8 dígitos", Snackbar.LENGTH_LONG).show(); return }
        val mode = PosPaymentMode.entries[binding.spinnerPosPaymentMode.selectedItemPosition]
        val method = PosPaymentMethod.entries[binding.spinnerPosPaymentMethod.selectedItemPosition.coerceAtLeast(0)]
        val total = cart.sumOf { it.subtotal }
        val paid = when (mode) { PosPaymentMode.PAID -> total; PosPaymentMode.CREDIT -> 0.0; PosPaymentMode.PARTIAL -> binding.etPosAmountPaid.text?.toString()?.toDoubleOrNull() ?: 0.0 }
        if (mode == PosPaymentMode.PARTIAL && (paid <= 0 || paid >= total)) { Snackbar.make(binding.root, "El pago parcial debe ser mayor a cero y menor al total", Snackbar.LENGTH_LONG).show(); return }
        val checkout = PosCheckout(documentType, customerDocType, document, customer, binding.switchPosIgv.isChecked, mode, method, paid)
        val soldItems = cart.toList()
        saving = true; binding.btnCompletePosSale.isEnabled = false; binding.btnCompletePosSale.text = "Registrando venta…"
        executor.execute {
            try {
                val result = if (session.isLoggedIn) repository.createSale(soldItems, checkout)
                else localRepository?.createSale(soldItems, checkout) ?: PosSaleResult(false, "No se pudo abrir el catálogo local.")
                runOnUiThread {
                    saving = false; binding.btnCompletePosSale.isEnabled = true; binding.btnCompletePosSale.text = "Registrar y cobrar"
                    if (result.success) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        if (session.isLoggedIn) applySaleToCachedCatalog(soldItems) else loadCatalog(forceRefresh = true)
                        cart.clear(); resetCheckout(); updateCartUi(); showCatalog()
                    }
                    else Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    saving = false; binding.btnCompletePosSale.isEnabled = true; binding.btnCompletePosSale.text = "Registrar y cobrar"
                    if (error is SessionExpiredException) redirectToLogin() else Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo registrar la venta", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applySaleToCachedCatalog(soldItems: List<PosCartItem>) {
        val soldByProduct = soldItems
            .filter { it.type == PosCatalogType.PRODUCT && it.trackInventory }
            .groupBy { it.catalogId }
            .mapValues { (_, items) -> items.sumOf { it.quantity }.toDouble() }
        if (soldByProduct.isEmpty()) return
        catalog.replaceAll { item ->
            val soldQuantity = soldByProduct[item.id]
            if (soldQuantity == null || item.stock == null) item
            else item.copy(stock = (item.stock - soldQuantity).coerceAtLeast(0.0))
        }
        catalogCache.save(session.storeId, catalog)
        renderCatalog()
    }

    private fun resetCheckout() {
        binding.spinnerPosDocumentType.setSelection(0); binding.spinnerPosCustomerDocType.setSelection(0)
        binding.etPosCustomerDocument.setText("00000000"); binding.etPosCustomerName.setText("Consumidor Final")
        binding.switchPosIgv.isChecked = false; binding.spinnerPosPaymentMode.setSelection(0); binding.spinnerPosPaymentMethod.setSelection(0); binding.etPosAmountPaid.setText("")
    }

    private fun redirectToLogin() {
        session.logout(); Toast.makeText(this, "Tu sesión venció. Ingresa nuevamente.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }); finish()
    }

    private fun showCloudRequiredMessage() {
        Snackbar.make(binding.root, "Inicia sesión para usar respaldo y facturación electrónica.", Snackbar.LENGTH_LONG).show()
    }

    private fun spinnerAdapter(values: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    private fun selectionListener(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position); override fun onNothingSelected(parent: AdapterView<*>?) = Unit }
    private fun simpleWatcher(block: (String) -> Unit) = object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = block(s?.toString().orEmpty()); override fun afterTextChanged(s: Editable?) = Unit }
    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private enum class CatalogFilter { ALL, PRODUCTS, SERVICES }
}

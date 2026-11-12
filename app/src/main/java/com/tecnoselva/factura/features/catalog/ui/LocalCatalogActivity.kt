package com.tecnoselva.factura.features.catalog.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.content.DialogInterface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.databinding.ActivityLocalCatalogBinding
import com.tecnoselva.factura.databinding.DialogCatalogProductBinding
import com.tecnoselva.factura.databinding.ItemCatalogVariantEditorBinding
import com.tecnoselva.factura.databinding.ItemCatalogVariantSummaryBinding
import com.tecnoselva.factura.databinding.ItemCatalogWholesaleEditorBinding
import com.tecnoselva.factura.features.catalog.data.LocalCatalogRepository
import com.tecnoselva.factura.features.catalog.model.LocalCatalogProduct
import com.tecnoselva.factura.features.catalog.model.LocalCatalogVariant
import com.tecnoselva.factura.features.catalog.model.LocalWholesalePrice
import com.tecnoselva.factura.features.catalog.ui.components.LocalCatalogAdapter
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.purchases.ui.PurchasesActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity
import java.io.File
import java.util.UUID

class LocalCatalogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalCatalogBinding
    private lateinit var repository: LocalCatalogRepository
    private lateinit var adapter: LocalCatalogAdapter
    private lateinit var session: SessionManager
    private var products: List<LocalCatalogProduct> = emptyList()
    private var visibleProducts: List<LocalCatalogProduct> = emptyList()
    private var selectedCategory: String? = null
    private var imageSelectionHandler: ((Uri) -> Unit)? = null
    private var selectedImageUri: Uri? = null
    private var removeCurrentImage = false

    private val selectImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        result.data?.data?.let { uri -> imageSelectionHandler?.invoke(uri) }
    }

    private fun openGallery() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }.apply {
            type = "image/*"
        }
        selectImage.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalCatalogBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.CATALOG)
        session = SessionManager(this)
        val accountScope = if (session.isLoggedIn) {
            session.currentUserId.ifBlank { session.storeId }.ifBlank { session.userEmail }
        } else {
            LocalStoreProfileRepository(this).localStoreId
        }
        repository = LocalCatalogRepository(this, accountScope)
        adapter = LocalCatalogAdapter(::showProductDetail)

        binding.bottomNavigationLocalCatalog.selectedItemId = R.id.nav_inventory
        binding.bottomNavigationLocalCatalog.menu.findItem(R.id.nav_billing).isVisible = session.isLoggedIn
        binding.bottomNavigationLocalCatalog.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    openTopLevel(HomeActivity::class.java)
                    true
                }
                R.id.nav_sales -> { openTopLevel(PosActivity::class.java); true }
                R.id.nav_inventory -> true
                R.id.nav_billing -> { openTopLevel(DocumentsActivity::class.java); true }
                else -> false
            }
        }
        binding.rvLocalCatalog.layoutManager = GridLayoutManager(this, 2)
        binding.rvLocalCatalog.adapter = adapter
        binding.fabAddLocalProduct.setOnClickListener { showProductEditor(null, readOnly = false) }
        binding.etLocalCatalogSearch.doAfterTextChanged { render(it?.toString().orEmpty()) }
        binding.btnCatalogCategoryFilter.setOnClickListener { showCategoryFilter() }
        binding.btnOpenPurchases.setOnClickListener {
            startActivity(Intent(this, PurchasesActivity::class.java))
            applyNavigationTransition()
        }
        binding.btnExportCatalogPdf.setOnClickListener { exportVisibleCatalog() }
        reload()
    }

    private fun openTopLevel(destination: Class<out AppCompatActivity>) {
        startActivity(Intent(this, destination).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        applyNavigationTransition()
        finish()
    }



    private fun reload() {
        products = repository.getAll()
        refreshCategoryFilter()
        render(binding.etLocalCatalogSearch.text?.toString().orEmpty())
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) reload()
    }

    private fun refreshCategoryFilter() {
        val categories = repository.getCategories()
        if (selectedCategory != null && categories.none { it.equals(selectedCategory, ignoreCase = true) }) {
            selectedCategory = null
        }
        binding.btnCatalogCategoryFilter.contentDescription =
            "Filtrar por categoría: ${selectedCategory ?: ALL_CATEGORIES}"
    }

    private fun showCategoryFilter() {
        val options = listOf(ALL_CATEGORIES) + repository.getCategories()
        val selectedIndex = selectedCategory?.let { selected ->
            options.indexOfFirst { it.equals(selected, ignoreCase = true) }.coerceAtLeast(0)
        } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Filtrar por categoría")
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                selectedCategory = options[which].takeUnless { it == ALL_CATEGORIES }
                refreshCategoryFilter()
                render(binding.etLocalCatalogSearch.text?.toString().orEmpty())
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun render(query: String) {
        val normalized = query.trim().lowercase()
        val filtered = products.filter { product ->
            val categoryMatches = selectedCategory == null || product.category.equals(selectedCategory, ignoreCase = true)
            val searchMatches = normalized.isBlank() ||
                product.name.lowercase().contains(normalized) ||
                product.sku.lowercase().contains(normalized) ||
                product.category.lowercase().contains(normalized) ||
                product.variants.any { it.name.lowercase().contains(normalized) || it.sku.lowercase().contains(normalized) }
            categoryMatches && searchMatches
        }
        visibleProducts = filtered
        adapter.submitList(filtered)
        binding.btnExportCatalogPdf.isEnabled = filtered.isNotEmpty()
        binding.tvLocalCatalogCount.text = "${filtered.size} ${if (filtered.size == 1) "producto" else "productos"}"
        binding.emptyLocalCatalog.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvLocalCatalog.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmptyLocalCatalog.text = if (products.isEmpty()) {
            "Aún no tienes productos. Agrega el primero con el botón +."
        } else {
            "No hay productos que coincidan con tu búsqueda."
        }
    }

    private fun exportVisibleCatalog() {
        val exportProducts = visibleProducts.toList()
        if (exportProducts.isEmpty()) {
            Snackbar.make(binding.root, "No hay productos para generar el PDF", Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnExportCatalogPdf.isEnabled = false
        binding.btnExportCatalogPdf.text = "Generando…"
        Thread {
            try {
                val pdf = CatalogPdfGenerator(this).generate(
                    products = exportProducts,
                    businessName = session.businessName,
                    categoryLabel = selectedCategory ?: "Todas"
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, "Catalogo de productos")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Compartir catalogo PDF"))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo generar el PDF", Snackbar.LENGTH_LONG).show()
                    }
                }
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.btnExportCatalogPdf.text = "PDF"
                        binding.btnExportCatalogPdf.isEnabled = visibleProducts.isNotEmpty()
                    }
                }
            }
        }.start()
    }

    private fun showProductDetail(product: LocalCatalogProduct) {
        showProductEditor(product, readOnly = true)
    }

    private fun showProductEditor(product: LocalCatalogProduct?, readOnly: Boolean) {
        val editor = DialogCatalogProductBinding.inflate(layoutInflater)
        val variantDrafts = product?.variants.orEmpty().map { variant ->
            VariantDraft(
                id = variant.id,
                name = variant.name,
                imagePath = variant.imagePath,
                purchasePrice = variant.purchasePrice,
                salePrice = variant.salePrice,
                minimumPrice = variant.minimumPrice,
                stock = variant.stock,
                wholesalePrices = variant.wholesalePrices.toMutableList()
            )
        }.toMutableList()
        val wholesaleRows = mutableListOf<ItemCatalogWholesaleEditorBinding>()
        selectedImageUri = null
        removeCurrentImage = false
        val productSku = product?.sku?.takeIf { it.isNotBlank() } ?: repository.nextProductSku()

        editor.etCatalogName.setText(product?.name.orEmpty())
        editor.etCatalogSku.setText(productSku)
        editor.etCatalogSku.isFocusable = false
        editor.etCatalogSku.isClickable = false
        val categoryOptions = repository.getCategories()
        editor.etCatalogCategory.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categoryOptions)
        )
        editor.etCatalogCategory.threshold = 0
        editor.etCatalogCategory.setText(product?.category.orEmpty(), false)
        editor.etCatalogCategory.setOnClickListener {
            if (!readOnly && categoryOptions.isNotEmpty()) editor.etCatalogCategory.showDropDown()
        }
        editor.etCatalogCategory.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !readOnly && categoryOptions.isNotEmpty()) editor.etCatalogCategory.showDropDown()
        }
        editor.etCatalogPurchasePrice.setText(product?.purchasePrice?.moneyInput().orEmpty())
        editor.etCatalogSalePrice.setText(product?.salePrice?.moneyInput().orEmpty())
        editor.etCatalogMinimumPrice.setText(product?.minimumPrice?.moneyInput().orEmpty())
        editor.etCatalogStock.setText(product?.stock?.quantityInput().orEmpty())
        editor.switchCatalogVariants.isChecked = product?.variantsEnabled == true
        product?.imagePath?.let { path ->
            File(path).takeIf { it.exists() }?.let {
                editor.ivCatalogProductImage.setImageURI(Uri.fromFile(it))
                editor.ivCatalogProductImage.setPadding(0, 0, 0, 0)
            }
        }
        editor.btnRemoveCatalogImage.visibility = if (product?.imagePath?.let { File(it).exists() } == true) View.VISIBLE else View.GONE

        fun refreshVariantImages() {
            val variantImageUris = variantDrafts.mapNotNull { draft ->
                draft.selectedUri ?: draft.imagePath.takeIf { it.isNotBlank() && File(it).exists() }
                    ?.let { Uri.fromFile(File(it)) }
            }
            if (variantImageUris.isNotEmpty()) {
                editor.ivCatalogProductImage.visibility = View.GONE
                editor.rvCatalogImageCarousel.visibility = View.VISIBLE
                editor.rvCatalogImageCarousel.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
                editor.rvCatalogImageCarousel.adapter = VariantImageCarouselAdapter(variantImageUris)
                editor.rvCatalogImageCarousel.onFlingListener = null
                PagerSnapHelper().attachToRecyclerView(editor.rvCatalogImageCarousel)
            } else {
                editor.rvCatalogImageCarousel.visibility = View.GONE
                editor.ivCatalogProductImage.visibility = View.VISIBLE
            }
        }

        fun renderVariantList() {
            editor.containerCatalogVariants.removeAllViews()
            variantDrafts.forEachIndexed { index, draft ->
                val row = ItemCatalogVariantSummaryBinding.inflate(layoutInflater, editor.containerCatalogVariants, false)
                row.tvVariantSummaryName.text = draft.name.ifBlank { "Nueva variante" }
                row.tvVariantSummaryDetails.text = "SKU $productSku-${index + 1}  •  S/ ${draft.salePrice.moneyInput()}  •  Stock ${draft.stock.quantityInput()}"
                when {
                    draft.selectedUri != null -> row.ivVariantSummaryImage.setImageURI(draft.selectedUri)
                    else -> File(draft.imagePath).takeIf { it.exists() }?.let { row.ivVariantSummaryImage.setImageURI(Uri.fromFile(it)) }
                }
                row.btnEditVariant.setOnClickListener { showVariantDialog(draft, productSku, editor, readOnly) { updated ->
                    variantDrafts[index] = updated
                    renderVariantList()
                } }
                row.btnRemoveVariant.setOnClickListener {
                    variantDrafts.removeAt(index)
                    renderVariantList()
                }
                if (readOnly) {
                    row.root.setOnClickListener { showVariantDialog(draft, productSku, editor, readOnly = true) {} }
                    row.btnEditVariant.visibility = View.GONE
                    row.btnRemoveVariant.visibility = View.GONE
                }
                editor.containerCatalogVariants.addView(row.root)
            }
            refreshVariantImages()
        }

        fun addVariant() {
            val draft = VariantDraft(
                id = null,
                name = "",
                imagePath = "",
                purchasePrice = editor.etCatalogPurchasePrice.decimal() ?: 0.0,
                salePrice = editor.etCatalogSalePrice.decimal() ?: 0.0,
                minimumPrice = editor.etCatalogMinimumPrice.decimal() ?: 0.0,
                stock = 0.0
            )
            showVariantDialog(draft, productSku, editor, readOnly = false) { saved ->
                variantDrafts += saved
                renderVariantList()
            }
        }

        fun addWholesale(price: LocalWholesalePrice? = null) {
            val row = ItemCatalogWholesaleEditorBinding.inflate(layoutInflater, editor.containerCatalogWholesale, false)
            row.etWholesaleQuantity.setText(price?.minimumQuantity?.toString().orEmpty())
            row.etWholesalePrice.setText(price?.unitPrice?.moneyInput().orEmpty())
            row.btnRemoveWholesale.setOnClickListener {
                editor.containerCatalogWholesale.removeView(row.root)
                wholesaleRows.remove(row)
            }
            row.root.tag = price?.id
            wholesaleRows.add(row)
            editor.containerCatalogWholesale.addView(row.root)
        }

        renderVariantList()
        product?.wholesalePrices?.sortedBy { it.minimumQuantity }?.forEach { addWholesale(it) }
        fun updateVariantSection(enabled: Boolean) {
            editor.sectionCatalogVariants.visibility = if (enabled) View.VISIBLE else View.GONE
            editor.cardCatalogBasePricing.visibility = if (enabled) View.GONE else View.VISIBLE
            editor.cardCatalogWholesale.visibility = if (enabled) View.GONE else View.VISIBLE
        }
        updateVariantSection(editor.switchCatalogVariants.isChecked)
        editor.switchCatalogVariants.setOnCheckedChangeListener { _, enabled ->
            updateVariantSection(enabled)
            if (enabled && variantDrafts.isEmpty()) addVariant()
        }
        editor.btnAddCatalogVariant.setOnClickListener { addVariant() }
        editor.btnAddCatalogWholesale.setOnClickListener { addWholesale() }
        editor.btnSelectCatalogImage.setOnClickListener {
            imageSelectionHandler = { uri ->
                selectedImageUri = uri
                removeCurrentImage = false
                editor.ivCatalogProductImage.setImageURI(uri)
                editor.ivCatalogProductImage.setPadding(0, 0, 0, 0)
                editor.btnRemoveCatalogImage.visibility = View.VISIBLE
            }
            openGallery()
        }
        editor.btnRemoveCatalogImage.setOnClickListener {
            selectedImageUri = null
            removeCurrentImage = true
            editor.btnRemoveCatalogImage.visibility = View.GONE
            editor.ivCatalogProductImage.setImageResource(R.drawable.ic_store)
            val padding = (20 * resources.displayMetrics.density).toInt()
            editor.ivCatalogProductImage.setPadding(padding, padding, padding, padding)
        }

        if (readOnly) {
            imageSelectionHandler = null
            editor.imageActionsCatalog.visibility = View.GONE
            editor.btnSelectCatalogImage.visibility = View.GONE
            editor.btnRemoveCatalogImage.visibility = View.GONE
            editor.btnAddCatalogVariant.visibility = View.GONE
            editor.btnAddCatalogWholesale.visibility = View.GONE
            editor.switchCatalogVariants.isClickable = false
            editor.switchCatalogVariants.isFocusable = false
            val mainFields = listOf(
                editor.etCatalogName,
                editor.etCatalogSku,
                editor.etCatalogCategory,
                editor.etCatalogPurchasePrice,
                editor.etCatalogSalePrice,
                editor.etCatalogMinimumPrice,
                editor.etCatalogStock
            )
            mainFields.forEach { field ->
                field.isFocusable = false
                field.isClickable = false
                field.isCursorVisible = false
            }
            wholesaleRows.forEach { row ->
                row.btnRemoveWholesale.visibility = View.GONE
                listOf(row.etWholesaleQuantity, row.etWholesalePrice).forEach { field ->
                    field.isFocusable = false
                    field.isClickable = false
                    field.isCursorVisible = false
                }
            }
        }

        val dialogBuilder = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TecnoSelva_CatalogDialog)
            .setTitle(
                when {
                    readOnly -> product?.name.orEmpty()
                    product == null -> "Nuevo producto"
                    else -> "Editar producto"
                }
            )
            .setView(editor.root)
            .setNegativeButton(if (readOnly) "Cerrar" else "Cancelar", null)
            .setPositiveButton(if (readOnly) "Editar" else "Guardar", null)
        if (!readOnly && product != null) dialogBuilder.setNeutralButton("Eliminar", null)
        val dialog = dialogBuilder.create()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        dialog.window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawableResource(R.drawable.bg_catalog_dialog)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = maxHeight
                horizontalMargin = 0f
            }
        }
        dialog.setOnDismissListener {
            imageSelectionHandler = null
            selectedImageUri = null
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxHeight)
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(getColor(R.color.brand_text_secondary))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply {
                setTextColor(getColor(R.color.brand_green_dark))
                setOnClickListener {
                    if (readOnly && product != null) {
                        dialog.dismiss()
                        showProductEditor(product, readOnly = false)
                    } else {
                        saveEditor(dialog, editor, variantDrafts, wholesaleRows, product, productSku)
                    }
                }
            }
            if (!readOnly && product != null) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
                    setTextColor(getColor(R.color.brand_danger))
                    setOnClickListener {
                        dialog.dismiss()
                        confirmDelete(product)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showVariantDialog(
        draft: VariantDraft,
        productSku: String,
        productEditor: DialogCatalogProductBinding,
        readOnly: Boolean,
        onSaved: (VariantDraft) -> Unit
    ) {
        val form = ItemCatalogVariantEditorBinding.inflate(layoutInflater)
        val imageState = VariantImageState(draft.id, draft.imagePath).apply {
            selectedUri = draft.selectedUri
            removeImage = draft.removeImage
        }
        val wholesaleRows = mutableListOf<ItemCatalogWholesaleEditorBinding>()
        val isNewVariant = draft.id == null && draft.name.isBlank()

        form.etVariantName.setText(draft.name)
        form.etVariantSku.setText("$productSku-${productEditor.containerCatalogVariants.childCount + 1}")
        form.etVariantPurchasePrice.setText(if (isNewVariant) "" else draft.purchasePrice.moneyInput())
        form.etVariantSalePrice.setText(if (isNewVariant) "" else draft.salePrice.moneyInput())
        form.etVariantMinimumPrice.setText(if (isNewVariant) "" else draft.minimumPrice.moneyInput())
        form.etVariantStock.setText(if (isNewVariant) "" else draft.stock.quantityInput())
        File(draft.imagePath).takeIf { it.exists() }?.let { image ->
            form.ivVariantImage.setImageURI(Uri.fromFile(image))
            form.ivVariantImage.setPadding(0, 0, 0, 0)
            form.btnRemoveVariantImage.visibility = View.VISIBLE
        }

        fun addWholesale(price: LocalWholesalePrice? = null) {
            val row = ItemCatalogWholesaleEditorBinding.inflate(layoutInflater, form.containerVariantWholesale, false)
            row.etWholesaleQuantity.setText(price?.minimumQuantity?.toString().orEmpty())
            row.etWholesalePrice.setText(price?.unitPrice?.moneyInput().orEmpty())
            row.root.tag = price?.id
            row.btnRemoveWholesale.setOnClickListener {
                form.containerVariantWholesale.removeView(row.root)
                wholesaleRows.remove(row)
            }
            wholesaleRows += row
            form.containerVariantWholesale.addView(row.root)
        }
        draft.wholesalePrices.sortedBy { it.minimumQuantity }.forEach { addWholesale(it) }
        form.btnAddVariantWholesale.setOnClickListener { addWholesale() }
        form.btnSelectVariantImage.setOnClickListener {
            imageSelectionHandler = { uri ->
                imageState.selectedUri = uri
                imageState.removeImage = false
                form.ivVariantImage.setImageURI(uri)
                form.ivVariantImage.setPadding(0, 0, 0, 0)
                form.btnRemoveVariantImage.visibility = View.VISIBLE
            }
            openGallery()
        }
        form.btnRemoveVariantImage.setOnClickListener {
            imageState.selectedUri = null
            imageState.removeImage = true
            form.ivVariantImage.setImageResource(R.drawable.ic_store)
            val padding = (16 * resources.displayMetrics.density).toInt()
            form.ivVariantImage.setPadding(padding, padding, padding, padding)
            form.btnRemoveVariantImage.visibility = View.GONE
        }
        form.root.tag = imageState
        form.btnRemoveVariant.visibility = View.GONE

        val builder = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TecnoSelva_CatalogDialog)
            .setTitle(if (draft.name.isBlank()) "Agregar variante" else "Editar variante")
            .setView(form.root)
            .setNegativeButton(if (readOnly) "Cerrar" else "Cancelar", null)
            .setPositiveButton(if (readOnly) "Cerrar" else "Guardar", null)
        val dialog = builder.create()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).toInt()
        dialog.setOnDismissListener { imageSelectionHandler = null }
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, maxHeight)
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positive.setTextColor(getColor(R.color.brand_green_dark))
            if (readOnly) {
                positive.setOnClickListener { dialog.dismiss() }
                form.btnSelectVariantImage.visibility = View.GONE
                form.btnRemoveVariantImage.visibility = View.GONE
                form.btnAddVariantWholesale.visibility = View.GONE
                wholesaleRows.forEach { row ->
                    row.btnRemoveWholesale.visibility = View.GONE
                    listOf(row.etWholesaleQuantity, row.etWholesalePrice).forEach { field ->
                        field.isFocusable = false
                        field.isClickable = false
                        field.isCursorVisible = false
                    }
                }
                listOf(form.etVariantName, form.etVariantSku, form.etVariantPurchasePrice, form.etVariantSalePrice, form.etVariantMinimumPrice, form.etVariantStock)
                    .forEach { field ->
                        field.isFocusable = false
                        field.isClickable = false
                        field.isCursorVisible = false
                    }
            } else {
                positive.setOnClickListener {
                    val name = form.etVariantName.text?.toString()?.trim().orEmpty()
                    val purchase = form.etVariantPurchasePrice.decimal()
                    val sale = form.etVariantSalePrice.decimal()
                    val minimum = form.etVariantMinimumPrice.decimal()
                    val stock = form.etVariantStock.decimal(default = 0.0)
                    if (name.isBlank() || purchase == null || sale == null || minimum == null || stock == null ||
                        purchase < 0 || sale < 0 || minimum < 0 || stock < 0 || minimum > sale) {
                        showFormError(form.root, "Revisa los datos de la variante")
                        return@setOnClickListener
                    }
                    val wholesale = mutableListOf<LocalWholesalePrice>()
                    wholesaleRows.forEachIndexed { index, row ->
                        val quantity = row.etWholesaleQuantity.text?.toString()?.trim()?.toIntOrNull()
                        val price = row.etWholesalePrice.decimal()
                        if (quantity == null || quantity < 2 || price == null || price <= purchase) {
                            showFormError(form.root, "Revisa el mayorista ${index + 1}: cantidad mínima 2 y precio mayor al costo")
                            return@setOnClickListener
                        }
                        wholesale += LocalWholesalePrice(row.root.tag as? String ?: UUID.randomUUID().toString(), quantity, price)
                    }
                    if (wholesale.map { it.minimumQuantity }.distinct().size != wholesale.size) {
                        showFormError(form.root, "No repitas cantidades mayoristas")
                        return@setOnClickListener
                    }
                    onSaved(draft.copy(
                        name = name,
                        purchasePrice = purchase,
                        salePrice = sale,
                        minimumPrice = minimum,
                        stock = stock,
                        selectedUri = imageState.selectedUri,
                        removeImage = imageState.removeImage,
                        wholesalePrices = wholesale.sortedBy { it.minimumQuantity }.toMutableList()
                    ))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun saveEditor(
        dialog: AlertDialog,
        editor: DialogCatalogProductBinding,
        variantDrafts: List<VariantDraft>,
        wholesaleRows: List<ItemCatalogWholesaleEditorBinding>,
        current: LocalCatalogProduct?,
        productSku: String
    ) {
        editor.layoutCatalogName.error = null
        editor.layoutCatalogPurchasePrice.error = null
        editor.layoutCatalogSalePrice.error = null
        editor.layoutCatalogMinimumPrice.error = null
        val name = editor.etCatalogName.text?.toString()?.trim().orEmpty()
        val variantsEnabled = editor.switchCatalogVariants.isChecked
        val purchaseValue = editor.etCatalogPurchasePrice.decimal()
        val saleValue = editor.etCatalogSalePrice.decimal()
        val minimumValue = editor.etCatalogMinimumPrice.decimal()
        val stockValue = editor.etCatalogStock.decimal(default = 0.0)
        val purchasePrice = purchaseValue ?: 0.0
        val salePrice = saleValue ?: 0.0
        val minimumPrice = minimumValue ?: 0.0
        val stock = stockValue ?: 0.0
        if (name.isBlank()) { editor.layoutCatalogName.error = "Ingresa el nombre"; return }
        if (!variantsEnabled) {
            if (purchaseValue == null || purchasePrice < 0) { editor.layoutCatalogPurchasePrice.error = "Ingresa un precio válido"; return }
            if (saleValue == null || salePrice < 0) { editor.layoutCatalogSalePrice.error = "Ingresa un precio válido"; return }
            if (minimumValue == null || minimumPrice < 0) { editor.layoutCatalogMinimumPrice.error = "Ingresa un precio válido"; return }
            if (minimumPrice > salePrice) { editor.layoutCatalogMinimumPrice.error = "No puede superar el precio de venta"; return }
            if (stockValue == null || stock < 0) { showFormError(editor.root, "El stock debe ser cero o mayor"); return }
        }

        val variants = mutableListOf<LocalCatalogVariant>()
        if (variantsEnabled) {
            if (variantDrafts.isEmpty()) { showFormError(editor.root, "Agrega al menos una variante"); return }
            variantDrafts.forEachIndexed { index, draft ->
                val variantName = draft.name.trim()
                val variantPurchase = draft.purchasePrice
                val variantSale = draft.salePrice
                val variantMinimum = draft.minimumPrice
                val variantStock = draft.stock
                if (variantName.isBlank() ||
                    variantPurchase < 0 || variantSale < 0 || variantMinimum < 0 || variantStock < 0 || variantMinimum > variantSale) {
                    showFormError(editor.root, "Revisa los datos de la variante ${index + 1}")
                    return
                }
                val variantWholesale = draft.wholesalePrices
                variantWholesale.forEachIndexed { wholesaleIndex, wholesale ->
                    val quantity = wholesale.minimumQuantity
                    val price = wholesale.unitPrice
                    if (quantity < 2 || price <= variantPurchase) {
                        showFormError(
                            editor.root,
                            "Revisa el mayorista ${wholesaleIndex + 1} de $variantName: cantidad mínima 2 y precio mayor al costo de compra"
                        )
                        return
                    }
                }
                if (variantWholesale.map { it.minimumQuantity }.distinct().size != variantWholesale.size) {
                    showFormError(editor.root, "No repitas cantidades mayoristas en $variantName")
                    return
                }
                val resolvedImagePath = try {
                    resolveVariantImagePath(draft)
                } catch (error: Exception) {
                    showFormError(editor.root, error.localizedMessage ?: "No se pudo guardar la imagen de la variante ${index + 1}")
                    return
                }
                variants += LocalCatalogVariant(
                    id = draft.id ?: UUID.randomUUID().toString(),
                    name = variantName,
                    sku = "$productSku-${index + 1}",
                    imagePath = resolvedImagePath,
                    purchasePrice = variantPurchase,
                    salePrice = variantSale,
                    minimumPrice = variantMinimum,
                    stock = variantStock,
                    wholesalePrices = variantWholesale.sortedBy { it.minimumQuantity }
                )
            }
        }

        val wholesale = mutableListOf<LocalWholesalePrice>()
        if (!variantsEnabled) {
            wholesaleRows.forEachIndexed { index, row ->
                val quantity = row.etWholesaleQuantity.text?.toString()?.trim()?.toIntOrNull()
                val price = row.etWholesalePrice.decimal()
                if (quantity == null || quantity < 2 || price == null || price <= purchasePrice) {
                    showFormError(
                        editor.root,
                        "Revisa el precio mayorista ${index + 1}: cantidad mínima 2 y precio mayor al costo de compra"
                    )
                    return
                }
                wholesale += LocalWholesalePrice(
                    id = row.root.tag as? String ?: UUID.randomUUID().toString(),
                    minimumQuantity = quantity,
                    unitPrice = price
                )
            }
            if (wholesale.map { it.minimumQuantity }.distinct().size != wholesale.size) {
                showFormError(editor.root, "No repitas una cantidad en los precios mayoristas")
                return
            }
        }

        val previousImage = current?.imagePath.orEmpty()
        val imagePath = try {
            when {
                selectedImageUri != null -> repository.importImage(selectedImageUri!!, previousImage)
                removeCurrentImage -> { repository.deleteOwnedImage(previousImage); "" }
                else -> previousImage
            }
        } catch (error: Exception) {
            showFormError(editor.root, error.localizedMessage ?: "No se pudo guardar la imagen")
            return
        }
        val visibleProductImagePath = if (variantsEnabled) {
            variants.firstOrNull { it.imagePath.isNotBlank() }?.imagePath ?: imagePath
        } else imagePath
        val savedProduct = LocalCatalogProduct(
                id = current?.id ?: UUID.randomUUID().toString(),
                name = name,
                sku = productSku,
                category = editor.etCatalogCategory.text?.toString()?.trim().orEmpty(),
                imagePath = visibleProductImagePath,
                purchasePrice = purchasePrice,
                salePrice = salePrice,
                minimumPrice = minimumPrice,
                stock = stock,
                variantsEnabled = variantsEnabled,
                variants = variants,
                wholesalePrices = wholesale.sortedBy { it.minimumQuantity },
                updatedAt = System.currentTimeMillis()
        )
        repository.save(savedProduct)
        val retainedVariantIds = savedProduct.variants.map { it.id }.toSet()
        current?.variants
            ?.filterNot { it.id in retainedVariantIds }
            ?.forEach { repository.deleteOwnedImage(it.imagePath) }
        dialog.dismiss()
        reload()
        Snackbar.make(binding.root, "Producto guardado localmente", Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmDelete(product: LocalCatalogProduct) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar producto")
            .setMessage("¿Eliminar “${product.name}” y su imagen del catálogo local?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                repository.delete(product)
                reload()
                Snackbar.make(binding.root, "Producto eliminado", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showFormError(view: View, message: String) = Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
    private fun android.widget.EditText.decimal(default: Double? = null): Double? =
        text?.toString()?.trim()?.replace(',', '.')?.takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: default
    private fun Double.moneyInput() = if (this == 0.0) "0" else String.format(java.util.Locale.US, "%.2f", this)
    private fun Double.quantityInput() = if (this % 1.0 == 0.0) toInt().toString() else toString()

    private fun resolveVariantImagePath(draft: VariantDraft): String {
        return when {
            draft.selectedUri != null -> repository.importImage(draft.selectedUri!!, draft.imagePath)
            draft.removeImage -> {
                repository.deleteOwnedImage(draft.imagePath)
                ""
            }
            else -> draft.imagePath
        }
    }

    private data class VariantDraft(
        val id: String?,
        var name: String,
        var imagePath: String,
        var purchasePrice: Double,
        var salePrice: Double,
        var minimumPrice: Double,
        var stock: Double,
        var selectedUri: Uri? = null,
        var removeImage: Boolean = false,
        var wholesalePrices: MutableList<LocalWholesalePrice> = mutableListOf()
    )

    private data class VariantImageState(
        val variantId: String?,
        val originalImagePath: String,
        var selectedUri: Uri? = null,
        var removeImage: Boolean = false,
        val wholesaleRows: MutableList<ItemCatalogWholesaleEditorBinding> = mutableListOf()
    )

    private class VariantImageCarouselAdapter(
        private val imageUris: List<Uri>
    ) : RecyclerView.Adapter<VariantImageCarouselAdapter.ImageViewHolder>() {
        class ImageViewHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(28, 28, 28, 28)
                contentDescription = "Imagen de variante"
            }
            return ImageViewHolder(image)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            holder.image.setImageURI(imageUris[position])
        }

        override fun getItemCount(): Int = imageUris.size
    }

    private companion object {
        const val ALL_CATEGORIES = "Todas las categorías"
    }
}

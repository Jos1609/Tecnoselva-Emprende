package com.tecnoselva.factura.features.home.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.databinding.ActivityMainBinding
import com.tecnoselva.factura.features.billing.ui.CreateInvoiceActivity
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity
import com.tecnoselva.factura.features.catalog.data.LocalCatalogRepository
import com.tecnoselva.factura.features.auth.ui.LoginActivity
import com.tecnoselva.factura.features.auth.ui.SubscriptionBlockedActivity
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.local.model.LocalStoreProfile
import com.tecnoselva.factura.databinding.DialogLocalStoreProfileBinding
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import java.util.Locale

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var localProfileRepository: LocalStoreProfileRepository
    private var guestMode = false
    private var selectedLocalLogoUri: Uri? = null
    private var localLogoSelectionHandler: ((Uri) -> Unit)? = null

    private val selectLocalLogo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { localLogoSelectionHandler?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        localProfileRepository = LocalStoreProfileRepository(this)
        guestMode = !sessionManager.isLoggedIn

        if (!guestMode && !sessionManager.isSubscriptionActive) {
            startActivity(Intent(this, SubscriptionBlockedActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.HOME)
        refreshDashboard()
        if (guestMode && localProfileRepository.getProfile()?.termsVersion != TERMS_VERSION) showLocalProfileDialog()
        binding.appBarMain.contentMain.btnCreateInvoice.setOnClickListener {
            if (guestMode) showPremiumFeatureDialog() else {
                startActivity(Intent(this, CreateInvoiceActivity::class.java))
                applyNavigationTransition()
            }
        }
        binding.appBarMain.contentMain.btnOpenPos.setOnClickListener { openPos() }
        binding.appBarMain.contentMain.btnOpenCatalog.setOnClickListener { openLocalCatalog() }
        binding.appBarMain.contentMain.btnOpenDocuments.visibility = if (guestMode) View.GONE else View.VISIBLE
        binding.appBarMain.contentMain.btnOpenDocuments.setOnClickListener { openDocuments() }
        binding.appBarMain.contentMain.btnOpenSettings.setOnClickListener { openSettings() }
        binding.appBarMain.bottomNavigation?.selectedItemId = R.id.nav_home
        binding.appBarMain.bottomNavigation?.menu?.findItem(R.id.nav_billing)?.isVisible = !guestMode
        binding.appBarMain.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_sales -> { openPos(); false }
                R.id.nav_inventory -> { openLocalCatalog(); false }
                R.id.nav_billing -> { openDocuments(); false }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshDashboard()
    }

    private fun refreshDashboard() {
        val profile = if (guestMode) localProfileRepository.getProfile() else null
        val accountScope = if (guestMode) localProfileRepository.localStoreId else sessionManager.currentUserId
            .ifBlank { sessionManager.storeId }
            .ifBlank { sessionManager.userEmail }
        val catalogRepository = LocalCatalogRepository(this, accountScope)
        val products = catalogRepository.getAll()
        val totalUnits = products.sumOf { it.totalStock }
        val lowStock = products.count { it.totalStock <= LOW_STOCK_LIMIT }
        val inventoryValue = products.sumOf { product ->
            if (product.variantsEnabled && product.variants.isNotEmpty()) {
                product.variants.sumOf { variant -> variant.purchasePrice * variant.stock }
            } else {
                product.purchasePrice * product.stock
            }
        }
        with(binding.appBarMain.contentMain) {
            tvBusinessInfo.text = profile?.businessName ?: sessionManager.businessName.takeIf { !guestMode } ?: "Mi emprendimiento"
            tvAccountInfo.text = if (guestMode) "Modo local · Tienda ${accountScope.take(8)}" else sessionManager.userEmail
            profile?.logoPath?.let { path ->
                File(path).takeIf { it.exists() }?.let {
                    ivHomeBusinessLogo.setImageURI(Uri.fromFile(it))
                    ivHomeBusinessLogo.visibility = View.VISIBLE
                }
            } ?: run { ivHomeBusinessLogo.visibility = View.GONE }
            tvHomeProductCount.text = products.size.toString()
            tvHomeUnitsCount.text = formatQuantity(totalUnits)
            tvHomeLowStockCount.text = lowStock.toString()
            tvHomeCategoryCount.text = catalogRepository.getCategories().size.toString()
            tvHomeInventoryValue.text = "S/ ${String.format(Locale.US, "%.2f", inventoryValue)}"
        }
    }

    private fun openDocuments() {
        if (guestMode) { showPremiumFeatureDialog(); return }
        startActivity(Intent(this, DocumentsActivity::class.java))
        applyNavigationTransition()
    }

    private fun openPos() {
        startActivity(Intent(this, PosActivity::class.java))
        applyNavigationTransition()
    }

    private fun openLocalCatalog() {
        startActivity(Intent(this, LocalCatalogActivity::class.java))
        applyNavigationTransition()
    }

    private fun openOrders() {
        startActivity(Intent(this, OrdersActivity::class.java))
        applyNavigationTransition()
    }



    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        applyNavigationTransition()
    }


    private fun showLocalProfileDialog() {
        val form = DialogLocalStoreProfileBinding.inflate(layoutInflater)
        val existing = localProfileRepository.getProfile()
        form.etLocalBusinessName.setText(existing?.businessName.orEmpty())
        form.etLocalAddress.setText(existing?.address.orEmpty())
        form.etLocalPhone.setText(existing?.phone.orEmpty())
        form.checkLocalTerms.isChecked = existing?.termsVersion == TERMS_VERSION
        existing?.logoPath?.let { path ->
            java.io.File(path).takeIf { it.exists() }?.let {
                form.ivLocalStoreLogo.setImageURI(Uri.fromFile(it))
                form.ivLocalStoreLogo.setPadding(0, 0, 0, 0)
            }
        }
        form.btnSelectLocalStoreLogo.setOnClickListener {
            localLogoSelectionHandler = { uri ->
                selectedLocalLogoUri = uri
                form.ivLocalStoreLogo.setImageURI(uri)
                form.ivLocalStoreLogo.setPadding(0, 0, 0, 0)
            }
            selectLocalLogo.launch(Intent(Intent.ACTION_PICK).apply { type = "image/*" })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Configura tu emprendimiento")
            .setView(form.root)
            .setPositiveButton("Empezar", null)
            .setNegativeButton("Salir") { _, _ -> finishAffinity() }
            .create()
        dialog.setCancelable(false)
        dialog.setOnDismissListener { localLogoSelectionHandler = null; selectedLocalLogoUri = null }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = form.etLocalBusinessName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) { form.layoutLocalBusinessName.error = "Ingresa el nombre del negocio"; return@setOnClickListener }
                if (!form.checkLocalTerms.isChecked) {
                    Snackbar.make(binding.root, "Debes aceptar los términos y condiciones", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val previousLogo = existing?.logoPath.orEmpty()
                val logoPath = try {
                    selectedLocalLogoUri?.let { localProfileRepository.importLogo(it, previousLogo) } ?: previousLogo
                } catch (error: Exception) {
                    Snackbar.make(binding.root, error.localizedMessage ?: "No se pudo guardar el logo", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                localProfileRepository.save(LocalStoreProfile(
                    localStoreId = localProfileRepository.localStoreId,
                    businessName = name,
                    address = form.etLocalAddress.text?.toString()?.trim().orEmpty(),
                    phone = form.etLocalPhone.text?.toString()?.trim().orEmpty(),
                    logoPath = logoPath,
                    termsVersion = TERMS_VERSION,
                    termsAcceptedAt = System.currentTimeMillis()
                ))
                dialog.dismiss()
                refreshDashboard()
            }
        }
        dialog.show()
    }

    private fun showPremiumFeatureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Función con cuenta")
            .setMessage("Inicia sesión para activar respaldo en la nube, clientes y facturación electrónica.")
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Iniciar sesión") { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
            }
            .show()
    }

    private fun formatQuantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private companion object {
        const val LOW_STOCK_LIMIT = 5.0
        const val TERMS_VERSION = "1.0"
    }

}

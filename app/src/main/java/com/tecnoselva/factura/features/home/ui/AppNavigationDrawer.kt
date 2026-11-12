package com.tecnoselva.factura.features.home.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.session.confirmLogout
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.features.catalog.ui.LocalCatalogActivity
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.pos.ui.PosActivity
import com.tecnoselva.factura.features.purchases.ui.PurchasesActivity
import com.tecnoselva.factura.features.settings.ui.SettingsActivity

object AppNavigationDrawer {
    enum class Section(val menuId: Int) {
        HOME(R.id.nav_home),
        SALES(R.id.nav_sales),
        ORDERS(R.id.nav_orders),
        CATALOG(R.id.nav_inventory),
        PURCHASES(R.id.nav_purchases),
        BILLING(R.id.nav_billing),
        SETTINGS(R.id.nav_settings)
    }

    fun setContentView(activity: Activity, content: View, selected: Section) {
        listOf(
            R.id.bottomNavigation,
            R.id.bottomNavigationPos,
            R.id.bottomNavigationOrders,
            R.id.bottomNavigationLocalCatalog,
            R.id.bottomNavigationDocuments,
            R.id.bottomNavigationPurchases
        ).forEach { id -> content.findViewById<View>(id)?.visibility = View.GONE }

        val drawer = DrawerLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val contentContainer = FrameLayout(activity)
        val toolbar = MaterialToolbar(activity).apply {
            title = selected.title
            setNavigationIcon(R.drawable.ic_menu)
            setNavigationContentDescription("Abrir menú de navegación")
            setNavigationOnClickListener { drawer.openDrawer(Gravity.START) }
            setTitleTextColor(activity.getColor(R.color.brand_text_primary))
            setBackgroundColor(activity.getColor(R.color.brand_card))
            elevation = dp(activity, 8).toFloat()
        }
        val contentSlot = FrameLayout(activity)
        val contentSlotParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(activity, 56) }
        contentSlot.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        contentContainer.addView(contentSlot, contentSlotParams)
        contentContainer.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 56)
        ).apply { gravity = Gravity.TOP })
        drawer.addView(contentContainer, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ))
        toolbar.bringToFront()

        val navigation = activity.layoutInflater
            .inflate(R.layout.view_navigation_drawer, drawer, false)
        val navigationItems = navigation.findViewById<LinearLayout>(R.id.navigationItems)
        val navigationRows = mutableMapOf<Int, LinearLayout>()
        val session = SessionManager(activity)

        fun addItem(id: Int, title: String, icon: Int, isLogout: Boolean = false) {
            val row = createNavigationItem(activity, id, title, icon, selected.menuId == id, isLogout)
            navigationRows[id] = row
            navigationItems.addView(row)
        }

        addItem(R.id.nav_home, "Inicio", R.drawable.ic_home)
        addItem(R.id.nav_sales, "Punto de venta", R.drawable.ic_cart)
        addItem(R.id.nav_orders, "Pedidos", R.drawable.ic_orders)
        addItem(R.id.nav_inventory, "Catálogo", R.drawable.ic_store)
        addItem(R.id.nav_purchases, "Compras", R.drawable.ic_cart)
        if (session.isLoggedIn) addItem(R.id.nav_billing, "Facturación", R.drawable.ic_document)

        val divider = View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.brand_border))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        navigationItems.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1)
        ).apply {
            topMargin = dp(activity, 12)
            bottomMargin = dp(activity, 12)
            marginStart = dp(activity, 24)
            marginEnd = dp(activity, 24)
        })
        val accountHeading = TextView(activity).apply {
            text = "Cuenta"
            setTextColor(activity.getColor(R.color.brand_text_secondary))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(activity, 24), dp(activity, 2), 0, dp(activity, 5))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        navigationItems.addView(accountHeading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        addItem(R.id.nav_settings, "Configuración", R.drawable.ic_settings_black_24dp)
        addItem(R.id.nav_logout, "Cerrar sesión", R.drawable.ic_logout, isLogout = true)

        navigation.elevation = activity.resources.displayMetrics.density * 12f
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val drawerWidth = minOf(
            dp(activity, 320),
            screenWidth - dp(activity, 80)
        ).coerceAtLeast(dp(activity, 260))
        val drawerParams = DrawerLayout.LayoutParams(
            drawerWidth,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.START }
        drawer.addView(navigation, drawerParams)

        activity.setContentView(drawer)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentContainer.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            navigation.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(drawer)
        navigationRows.forEach { (itemId, row) ->
            row.setOnClickListener {
                val destination = when (itemId) {
                    R.id.nav_home -> HomeActivity::class.java
                    R.id.nav_sales -> PosActivity::class.java
                    R.id.nav_orders -> OrdersActivity::class.java
                    R.id.nav_inventory -> LocalCatalogActivity::class.java
                    R.id.nav_purchases -> PurchasesActivity::class.java
                    R.id.nav_billing -> DocumentsActivity::class.java
                    R.id.nav_settings -> SettingsActivity::class.java
                    else -> null
                }
                if (itemId == R.id.nav_logout) {
                    drawer.closeDrawer(Gravity.START)
                    activity.confirmLogout()
                } else if (destination != null && activity::class.java == destination) {
                    drawer.closeDrawer(Gravity.START)
                } else if (destination != null) {
                    activity.startActivity(Intent(activity, destination))
                    activity.applyNavigationTransition()
                    activity.finish()
                }
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun createNavigationItem(
        activity: Activity,
        id: Int,
        title: String,
        iconRes: Int,
        selected: Boolean,
        logout: Boolean
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = if (selected) "$title, seleccionado" else title
            setBackgroundResource(R.drawable.bg_navigation_item)
            setPadding(dp(activity, 18), 0, dp(activity, 14), 0)
            minimumHeight = dp(activity, 58)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 58)
        ).apply {
            marginStart = dp(activity, 14)
            marginEnd = dp(activity, 14)
            topMargin = dp(activity, 3)
            bottomMargin = dp(activity, 3)
        }
        val tint = if (logout) null else ContextCompat.getColorStateList(activity, R.color.selector_navigation_icon)
        val icon = ImageView(activity).apply {
            setImageResource(iconRes)
            isSelected = selected
            if (logout) setColorFilter(activity.getColor(R.color.brand_danger)) else imageTintList = tint
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(activity, 26), dp(activity, 26)))

        val labelColors = if (logout) {
            ColorStateList.valueOf(activity.getColor(R.color.brand_danger))
        } else {
            ContextCompat.getColorStateList(activity, R.color.selector_navigation_text)
                ?: ColorStateList.valueOf(activity.getColor(R.color.brand_text_primary))
        }
        val label = TextView(activity).apply {
            text = title
            isSelected = selected
            textSize = 16f
            setTextColor(labelColors)
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(activity, 24)
        })

        if (!selected && !logout) {
            val chevron = ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                isSelected = selected
                imageTintList = ContextCompat.getColorStateList(activity, R.color.selector_navigation_icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(chevron, LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22)))
        }
        return row
    }

    private val Section.title: String
        get() = when (this) {
            Section.HOME -> "Inicio"
            Section.SALES -> "Punto de venta"
            Section.ORDERS -> "Pedidos"
            Section.CATALOG -> "Catálogo"
            Section.PURCHASES -> "Compras"
            Section.BILLING -> "Facturación"
            Section.SETTINGS -> "Configuración"
        }
}

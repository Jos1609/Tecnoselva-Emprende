package com.tecnoselva.factura.features.catalog.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import com.google.android.material.button.MaterialButtonToggleGroup
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.features.purchases.ui.PurchasesActivity

object CatalogNavigation {
    enum class Section(val buttonId: Int) {
        CATALOG(R.id.navCatalogProducts),
        PURCHASES(R.id.navCatalogPurchases)
    }

    fun bind(activity: Activity, root: View, selected: Section) {
        root.findViewById<MaterialButtonToggleGroup>(R.id.catalogNavigationGroup).check(selected.buttonId)
        root.findViewById<View>(R.id.navCatalogProducts).setOnClickListener {
            navigate(activity, LocalCatalogActivity::class.java)
        }
        root.findViewById<View>(R.id.navCatalogPurchases).setOnClickListener {
            navigate(activity, PurchasesActivity::class.java)
        }
    }

    private fun navigate(activity: Activity, destination: Class<*>) {
        if (activity::class.java == destination) return
        activity.startActivity(Intent(activity, destination))
        activity.applyNavigationTransition()
        activity.finish()
    }
}

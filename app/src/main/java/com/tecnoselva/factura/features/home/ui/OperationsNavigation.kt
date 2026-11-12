package com.tecnoselva.factura.features.home.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import com.google.android.material.button.MaterialButtonToggleGroup
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.ui.applyNavigationTransition
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.documents.ui.DocumentsActivity
import com.tecnoselva.factura.features.orders.ui.OrdersActivity
import com.tecnoselva.factura.features.pos.ui.PosActivity

object OperationsNavigation {
    enum class Section(val buttonId: Int) {
        SALES(R.id.navOperationsSales),
        ORDERS(R.id.navOperationsOrders),
        BILLING(R.id.navOperationsBilling)
    }

    fun bind(activity: Activity, root: View, selected: Section) {
        root.findViewById<MaterialButtonToggleGroup>(R.id.operationsNavigationGroup).check(selected.buttonId)
        root.findViewById<View>(R.id.navOperationsSales).setOnClickListener { navigate(activity, PosActivity::class.java) }
        root.findViewById<View>(R.id.navOperationsOrders).setOnClickListener { navigate(activity, OrdersActivity::class.java) }
        val billing = root.findViewById<View>(R.id.navOperationsBilling)
        billing.visibility = if (SessionManager(activity).isLoggedIn) View.VISIBLE else View.GONE
        billing.setOnClickListener { navigate(activity, DocumentsActivity::class.java) }
    }

    private fun navigate(activity: Activity, destination: Class<*>) {
        if (activity::class.java == destination) return
        activity.startActivity(Intent(activity, destination))
        activity.applyNavigationTransition()
        activity.finish()
    }
}

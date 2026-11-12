package com.tecnoselva.factura.features.purchases.data

import com.tecnoselva.factura.features.purchases.model.Purchase
import com.tecnoselva.factura.features.purchases.model.PurchaseDetail
import com.tecnoselva.factura.features.purchases.model.PurchaseDraftItem
import com.tecnoselva.factura.features.purchases.model.PurchaseProduct
import com.tecnoselva.factura.features.purchases.model.PurchaseVariant

interface PurchaseDataSource {
    fun getPurchases(search: String? = null, dateFrom: String? = null, dateTo: String? = null): List<Purchase>
    fun getPurchaseDetail(id: String): PurchaseDetail? = null
    fun searchProducts(search: String, limit: Int = 50): List<PurchaseProduct>
    fun searchVariants(productId: String): List<PurchaseVariant> = emptyList()
    fun createPurchase(items: List<PurchaseDraftItem>, invoice: String, supplier: String, ruc: String, date: String, discount: Double, notes: String): Boolean
    fun receivePurchase(id: String): Boolean
    fun cancelPurchase(id: String): Boolean
}

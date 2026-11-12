package com.tecnoselva.factura.features.purchases.model

data class Purchase(
    val id: String,
    val purchaseDate: String,
    val supplierName: String,
    val invoiceNumber: String,
    val total: Double,
    val status: String,
    val notes: String = ""
)

data class PurchaseDetail(
    val purchase: Purchase,
    val supplierRuc: String = "",
    val subtotal: Double = 0.0,
    val tax: Double = 0.0,
    val discount: Double = 0.0,
    val receivedAt: String = "",
    val receivedBy: String = "",
    val items: List<PurchaseDetailItem> = emptyList()
)

data class PurchaseDetailItem(
    val id: String,
    val productId: String,
    val productName: String,
    val sku: String = "",
    val variantId: String = "",
    val variantLabel: String = "",
    val quantity: Double,
    val unitCost: Double,
    val totalCost: Double,
    val previousStock: Double? = null,
    val currentStock: Double? = null
)

data class PurchaseProduct(val id: String, val name: String, val sku: String = "", val costPrice: Double = 0.0, val stock: Double = 0.0, val hasVariants: Boolean = false)

data class PurchaseVariant(
    val id: String,
    val productId: String,
    val name: String,
    val sku: String = "",
    val costPrice: Double = 0.0,
    val stock: Double = 0.0
)

data class PurchaseDraftItem(
    val productId: String,
    val productName: String,
    val sku: String,
    val quantity: Double,
    val unitCost: Double,
    val variantId: String? = null,
    val variantLabel: String = "",
    val stock: Double = 0.0
)

package com.tecnoselva.factura.features.catalog.model

data class LocalCatalogProduct(
    val id: String,
    val name: String,
    val sku: String = "",
    val category: String = "",
    val imagePath: String = "",
    val purchasePrice: Double,
    val salePrice: Double,
    val minimumPrice: Double,
    val stock: Double = 0.0,
    val variantsEnabled: Boolean = false,
    val variants: List<LocalCatalogVariant> = emptyList(),
    val wholesalePrices: List<LocalWholesalePrice> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayedMinimumPrice: Double
        get() = if (variantsEnabled && variants.isNotEmpty()) variants.minOf { it.minimumPrice } else minimumPrice

    val displayedSalePrice: Double
        get() = if (variantsEnabled && variants.isNotEmpty()) variants.minOf { it.salePrice } else salePrice

    val displayedPurchasePrice: Double
        get() = if (variantsEnabled && variants.isNotEmpty()) variants.minOf { it.purchasePrice } else purchasePrice

    val totalStock: Double
        get() = if (variantsEnabled && variants.isNotEmpty()) variants.sumOf { it.stock } else stock
}

data class LocalCatalogVariant(
    val id: String,
    val name: String,
    val sku: String = "",
    val imagePath: String = "",
    val purchasePrice: Double,
    val salePrice: Double,
    val minimumPrice: Double,
    val stock: Double = 0.0,
    val wholesalePrices: List<LocalWholesalePrice> = emptyList()
)

data class LocalWholesalePrice(
    val id: String,
    val minimumQuantity: Int,
    val unitPrice: Double
)

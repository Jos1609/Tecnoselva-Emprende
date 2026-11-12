package com.tecnoselva.factura.features.pos.data

import android.content.Context
import com.tecnoselva.factura.features.catalog.data.LocalCatalogRepository
import com.tecnoselva.factura.features.catalog.model.LocalCatalogProduct
import com.tecnoselva.factura.features.pos.model.PosCatalogItem
import com.tecnoselva.factura.features.pos.model.PosCatalogType
import com.tecnoselva.factura.features.pos.model.PosCartItem
import com.tecnoselva.factura.features.pos.model.PosCheckout
import com.tecnoselva.factura.features.pos.model.PosSaleResult
import com.tecnoselva.factura.features.orders.data.LocalOrderRepository

class LocalPosRepository(context: Context, localStoreId: String) {
    private val catalogRepository = LocalCatalogRepository(context, localStoreId)
    private val orderRepository = LocalOrderRepository(context, localStoreId)

    fun loadCatalog(): List<PosCatalogItem> = catalogRepository.getAll().map { product ->
        PosCatalogItem(
            id = product.id,
            type = PosCatalogType.PRODUCT,
            name = product.name,
            sku = product.sku,
            category = product.category.ifBlank { "Producto" },
            price = product.displayedSalePrice,
            stock = product.totalStock,
            trackInventory = true,
            hasVariants = product.variantsEnabled && product.variants.isNotEmpty()
        )
    }

    fun loadVariants(productId: String) = catalogRepository.getAll()
        .firstOrNull { it.id == productId }
        ?.variants
        .orEmpty()
        .map { variant ->
            com.tecnoselva.factura.features.pos.model.PosVariant(
                id = variant.id,
                label = variant.name,
                sku = variant.sku,
                price = variant.salePrice,
                stock = variant.stock
            )
        }

    fun createSale(cart: List<PosCartItem>, checkout: PosCheckout): PosSaleResult {
        if (cart.isEmpty()) return PosSaleResult(false, "Agrega al menos un producto.")
        val products = catalogRepository.getAll().toMutableList()
        cart.forEach { item ->
            val index = products.indexOfFirst { it.id == item.catalogId }
            if (index < 0) return PosSaleResult(false, "No se encontró ${item.name} en el catálogo local.")
            val product = products[index]
            products[index] = if (item.variantId != null) {
                product.copy(variants = product.variants.map { variant ->
                    if (variant.id == item.variantId) variant.copy(stock = (variant.stock - item.quantity).coerceAtLeast(0.0)) else variant
                })
            } else {
                product.copy(stock = (product.stock - item.quantity).coerceAtLeast(0.0))
            }
            catalogRepository.save(products[index])
        }
        orderRepository.recordSale(cart, checkout)
        return PosSaleResult(true, "Venta local registrada. Recuerda exportar respaldos periódicamente.")
    }
}

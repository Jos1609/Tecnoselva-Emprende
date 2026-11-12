package com.tecnoselva.factura.features.purchases.data

import android.content.Context
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.purchases.model.Purchase
import com.tecnoselva.factura.features.purchases.model.PurchaseProduct
import com.tecnoselva.factura.features.purchases.model.PurchaseDraftItem
import com.tecnoselva.factura.features.purchases.model.PurchaseDetail
import com.tecnoselva.factura.features.purchases.model.PurchaseDetailItem
import com.tecnoselva.factura.features.purchases.model.PurchaseVariant
import com.tecnoselva.factura.features.catalog.data.LocalCatalogRepository
import com.tecnoselva.factura.features.pos.data.PosCatalogCache

/** Compras cloud. Nunca debe usarse con una cuenta local: todas las operaciones exigen sesión. */
class PurchasesRepository(private val session: SessionRepository, context: Context) : PurchaseDataSource {
    private val localCatalog = LocalCatalogRepository(context, session.userId.ifBlank { session.storeId })
    private val posCatalogCache = PosCatalogCache(context)
    init {
        check(session.isLoggedIn && session.storeId.isNotBlank()) {
            "Las compras cloud requieren una cuenta con sesión iniciada y una tienda asociada."
        }
    }
    override fun getPurchases(search: String?, dateFrom: String?, dateTo: String?): List<Purchase> = session.withValidSession {
        val payload = JsonObject().apply {
            addProperty("p_store_id", session.storeId)
            add("p_status", JsonNull.INSTANCE)
            add("p_supplier_id", JsonNull.INSTANCE)
            if (dateFrom.isNullOrBlank()) add("p_date_from", JsonNull.INSTANCE) else addProperty("p_date_from", dateFrom)
            if (dateTo.isNullOrBlank()) add("p_date_to", JsonNull.INSTANCE) else addProperty("p_date_to", dateTo)
            if (search.isNullOrBlank()) add("p_search", JsonNull.INSTANCE) else addProperty("p_search", search.trim())
            addProperty("p_limit", 100)
            addProperty("p_offset", 0)
        }
        val rows = SupabaseService.callRpc("get_purchases", payload)
        val array = when {
            rows.isJsonArray -> rows.asJsonArray
            rows.isJsonObject -> rows.asJsonObject.getAsJsonArray("data") ?: rows.asJsonObject.getAsJsonArray("purchases")
            else -> null
        }
        array?.mapNotNull { element ->
            element.asJsonObject?.let { row ->
                Purchase(
                    id = row.text("id"),
                    purchaseDate = row.text("purchase_date"),
                    supplierName = row.text("supplier_name", "Sin proveedor"),
                    invoiceNumber = row.text("invoice_number"),
                    total = row.number("total"),
                    status = row.text("status", "pending"),
                    notes = row.text("notes")
                ).takeIf { it.id.isNotBlank() }
            }
        }.orEmpty()
    }

    override fun getPurchaseDetail(id: String): PurchaseDetail? = session.withValidSession {
        val result = SupabaseService.callRpc("get_purchase_by_id", JsonObject().apply {
            addProperty("p_purchase_id", id)
        })
        if (!result.isJsonObject) return@withValidSession null
        val row = result.asJsonObject
        val supplierName = row.supplierName()
        val purchase = Purchase(
            id = row.text("id", id),
            purchaseDate = row.text("purchase_date"),
            supplierName = supplierName,
            invoiceNumber = row.text("invoice_number"),
            total = row.number("total"),
            status = row.text("status", "pending"),
            notes = row.text("notes")
        )
        val itemsArray = row.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()
        val items = itemsArray.mapNotNull { element ->
            element.asJsonObject?.let { item ->
                val productId = item.text("product_id")
                if (productId.isBlank()) return@let null
                val quantity = item.number("quantity")
                val previousStock = item.numberOrNull("previous_stock")
                PurchaseDetailItem(
                    id = item.text("id", productId),
                    productId = productId,
                    productName = item.text("product_name", item.text("name", "Producto")),
                    sku = item.text("product_sku", item.text("sku")),
                    variantId = item.text("variant_id"),
                    variantLabel = item.text("variant_label"),
                    quantity = quantity,
                    unitCost = item.number("unit_cost"),
                    totalCost = item.numberOrNull("total_cost") ?: quantity * item.number("unit_cost"),
                    previousStock = previousStock,
                    currentStock = item.numberOrNull("current_stock")
                        ?: item.numberOrNull("stock")
                        ?: previousStock?.let { if (purchase.status == "received") it + quantity else it }
                )
            }
        }
        PurchaseDetail(
            purchase = purchase,
            supplierRuc = row.text("supplier_ruc", extractSupplierRuc(purchase.notes)),
            subtotal = row.number("subtotal"),
            tax = row.number("tax"),
            discount = row.number("discount").takeIf { it > 0.0 } ?: extractPurchaseDiscount(purchase.notes),
            receivedAt = row.text("received_at"),
            receivedBy = row.text("received_by"),
            items = items
        )
    }

    override fun searchProducts(search: String, limit: Int): List<PurchaseProduct> = session.withValidSession {
        val query = search.trim()
        if (query.length < 2) return@withValidSession emptyList()
        val result = SupabaseService.callRpc("get_products", JsonObject().apply {
            addProperty("p_store_id", session.storeId)
            add("p_category_id", JsonNull.INSTANCE)
            addProperty("p_status", "active")
            addProperty("p_search", query)
            addProperty("p_limit", limit)
            addProperty("p_offset", 0)
        })
        if (!result.isJsonArray) return@withValidSession emptyList()
        result.asJsonArray.mapNotNull { element -> element.asJsonObject?.let { row ->
            val hasVariants = row.bool("has_variants")
            PurchaseProduct(
                row.text("id"),
                row.text("name", "Producto"),
                row.text("sku"),
                row.number("cost_price"),
                if (hasVariants) row.numberOrNull("variants_total_stock") ?: row.numberOrNull("stock") ?: 0.0 else row.numberOrNull("stock") ?: 0.0,
                hasVariants
            )
        } }.filter { it.id.isNotBlank() }
    }

    override fun searchVariants(productId: String): List<PurchaseVariant> = session.withValidSession {
        val result = SupabaseService.callRpc("get_product_variants", JsonObject().apply {
            addProperty("p_product_id", productId)
        })
        if (!result.isJsonArray) return@withValidSession emptyList()
        result.asJsonArray.mapNotNull { element ->
            element.asJsonObject?.let { row ->
                if (!row.bool("active", true)) return@let null
                val id = row.text("id")
                if (id.isBlank()) return@let null
                val label = row.get("attributes")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { attribute ->
                        attribute.asJsonObject?.let { attr ->
                            "${attr.text("name")}: ${attr.text("value")}".takeIf { it != ": " }
                        }
                    }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(" / ")
                    .orEmpty()
                    .ifBlank { row.text("name", row.text("sku", "Variante")) }
                PurchaseVariant(
                    id = id,
                    productId = productId,
                    name = label,
                    sku = row.text("sku"),
                    costPrice = row.number("cost_price"),
                    stock = row.number("stock")
                )
            }
        }
    }

    override fun createPurchase(items: List<PurchaseDraftItem>, invoice: String, supplier: String, ruc: String, date: String, discount: Double, notes: String): Boolean = session.withValidSession {
        val payload = JsonObject().apply {
            addProperty("p_store_id", session.storeId)
            add("p_supplier_id", JsonNull.INSTANCE)
            addProperty("p_invoice_number", invoice.trim().ifBlank { null })
            addProperty("p_purchase_date", date)
            add("p_items", com.google.gson.JsonArray().apply {
                items.forEach { item ->
                    add(JsonObject().apply {
                        addProperty("product_id", item.productId)
                        item.variantId?.let { addProperty("variant_id", it) } ?: add("variant_id", JsonNull.INSTANCE)
                        item.variantLabel.takeIf { it.isNotBlank() }?.let { addProperty("variant_label", it) } ?: add("variant_label", JsonNull.INSTANCE)
                        addProperty("quantity", item.quantity)
                        addProperty("unit_cost", item.unitCost)
                    })
                }
            })
            addProperty("p_notes", listOf(supplier.trim().takeIf { it.isNotBlank() }, ruc.trim().takeIf { it.isNotBlank() }?.let { "RUC: $it" }, notes.trim().takeIf { it.isNotBlank() }, "[purchase_discount=${"%.2f".format(java.util.Locale.US, discount.coerceAtLeast(0.0))}]").filterNotNull().joinToString(" · ").ifBlank { null })
        }
        val result = SupabaseService.callRpc("create_purchase", payload)
        result.isJsonObject && (result.asJsonObject.get("success")?.asBoolean ?: false)
    }

    override fun receivePurchase(id: String): Boolean = session.withValidSession {
        val result = SupabaseService.callRpc("receive_purchase", JsonObject().apply {
            addProperty("p_purchase_id", id)
            addProperty("p_received_by", session.userId.ifBlank { null })
        })
        val success = result.isJsonObject && (result.asJsonObject.get("success")?.asBoolean ?: false)
        if (success) {
            syncLocalCatalogStock(id)
            posCatalogCache.invalidate(session.storeId)
        }
        success
    }

    override fun cancelPurchase(id: String): Boolean = session.withValidSession {
        val result = SupabaseService.callRpc("cancel_purchase", JsonObject().apply { addProperty("p_purchase_id", id) })
        result.isJsonObject && (result.asJsonObject.get("success")?.asBoolean ?: false)
    }

    private fun JsonObject.text(key: String, default: String = ""): String = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: default
    private fun JsonObject.supplierName(): String {
        val direct = listOf("supplier_name", "supplierName", "vendor_name", "supplier")
            .firstNotNullOfOrNull { key -> text(key).trim().takeIf { it.isNotBlank() } }
        if (direct != null) return direct

        val nested = listOf("supplier", "vendor", "supplier_data")
            .asSequence()
            .mapNotNull { key -> get(key)?.takeIf { it.isJsonObject }?.asJsonObject }
            .mapNotNull { supplier ->
                listOf("name", "supplier_name", "business_name", "legal_name")
                    .firstNotNullOfOrNull { key -> supplier.text(key).trim().takeIf { it.isNotBlank() } }
            }
            .firstOrNull()
        return nested ?: "Sin proveedor"
    }
    private fun JsonObject.number(key: String): Double = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
    private fun JsonObject.numberOrNull(key: String): Double? = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: default

    private fun extractPurchaseDiscount(notes: String): Double = Regex("\\[purchase_discount=([0-9.]+)\\]").find(notes)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    private fun extractSupplierRuc(notes: String): String = Regex("RUC:\\s*([^·\\n]+)").find(notes)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun syncLocalCatalogStock(purchaseId: String) {
        runCatching {
            val rows = SupabaseService.getRest("purchase_items?purchase_id=eq.$purchaseId&select=product_id,variant_id,quantity,unit_cost")
            if (!rows.isJsonArray) return@runCatching
            rows.asJsonArray.forEach { element ->
                val row = element.asJsonObject ?: return@forEach
                val productId = row.text("product_id")
                val variantId = row.text("variant_id")
                val quantity = row.number("quantity")
                val cost = row.number("unit_cost")
                val product = localCatalog.getAll().firstOrNull { it.id == productId } ?: return@forEach
                if (variantId.isNotBlank()) {
                    val variantIndex = product.variants.indexOfFirst { it.id == variantId }
                    if (variantIndex < 0) return@forEach
                    val variant = product.variants[variantIndex]
                    val nextStock = variant.stock + quantity
                    val nextCost = if (nextStock <= 0.0) cost else ((variant.stock * variant.purchasePrice) + (quantity * cost)) / nextStock
                    localCatalog.save(product.copy(variants = product.variants.toMutableList().apply {
                        this[variantIndex] = variant.copy(stock = nextStock, purchasePrice = nextCost.roundMoney())
                    }))
                } else {
                    val nextStock = product.stock + quantity
                    val nextCost = if (nextStock <= 0.0) cost else ((product.stock * product.purchasePrice) + (quantity * cost)) / nextStock
                    localCatalog.save(product.copy(stock = nextStock, purchasePrice = nextCost.roundMoney()))
                }
            }
        }
    }

    private fun Double.roundMoney() = kotlin.math.round(this * 100.0) / 100.0
}

package com.tecnoselva.factura.features.purchases.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tecnoselva.factura.features.catalog.data.LocalCatalogRepository
import com.tecnoselva.factura.features.purchases.model.Purchase
import com.tecnoselva.factura.features.purchases.model.PurchaseDraftItem
import com.tecnoselva.factura.features.purchases.model.PurchaseProduct
import com.tecnoselva.factura.features.purchases.model.PurchaseVariant
import com.tecnoselva.factura.features.purchases.model.PurchaseDetail
import com.tecnoselva.factura.features.purchases.model.PurchaseDetailItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.round

class LocalPurchasesRepository(context: Context, private val localStoreId: String) : PurchaseDataSource {
    private val preferences = context.getSharedPreferences("local_purchases_$localStoreId", Context.MODE_PRIVATE)
    private val catalog = LocalCatalogRepository(context, localStoreId)
    private val gson = Gson()

    override fun getPurchases(search: String?, dateFrom: String?, dateTo: String?): List<Purchase> = readRecords()
        .sortedByDescending { it.createdAt }
        .filter { record ->
            val query = search?.trim().orEmpty()
            val purchaseDate = record.purchaseDate.take(10)
            val dateMatches = (dateFrom.isNullOrBlank() || purchaseDate >= dateFrom) &&
                (dateTo.isNullOrBlank() || purchaseDate <= dateTo)
            dateMatches && (query.isBlank() || record.supplierName.contains(query, true) || record.invoiceNumber.contains(query, true) || record.items.any { it.productName.contains(query, true) })
        }
        .map { it.toPurchase() }

    override fun getPurchaseDetail(id: String): PurchaseDetail? {
        val record = readRecords().firstOrNull { it.id == id } ?: return null
        val subtotal = (record.total / 1.18).roundMoney()
        return PurchaseDetail(
            purchase = record.toPurchase(),
            supplierRuc = record.supplierRuc,
            subtotal = subtotal,
            tax = (record.total - subtotal).roundMoney(),
            discount = record.discount,
            receivedAt = record.receivedAt?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it)) }.orEmpty(),
            items = record.items.mapIndexed { index, item ->
                val product = catalog.getAll().firstOrNull { it.id == item.productId }
                val currentStock = if (!item.variantId.isNullOrBlank()) {
                    product?.variants?.firstOrNull { it.id == item.variantId }?.stock
                } else {
                    product?.stock
                }
                PurchaseDetailItem(
                    id = "${record.id}-$index",
                    productId = item.productId,
                    productName = item.productName,
                    sku = item.sku,
                    variantId = item.variantId.orEmpty(),
                    variantLabel = item.variantLabel,
                    quantity = item.quantity,
                    unitCost = item.unitCost,
                    totalCost = (item.quantity * item.unitCost).roundMoney(),
                    previousStock = item.previousStock,
                    currentStock = currentStock
                )
            }
        )
    }

    override fun searchProducts(search: String, limit: Int): List<PurchaseProduct> {
        val query = search.trim()
        if (query.length < 2) return emptyList()
        return catalog.getAll()
            .filter { it.name.contains(query, true) || it.sku.contains(query, true) }
            .take(limit)
            .map { product -> PurchaseProduct(product.id, product.name, product.sku, product.purchasePrice, product.totalStock, product.variantsEnabled) }
    }

    override fun searchVariants(productId: String): List<PurchaseVariant> = catalog.getAll()
        .firstOrNull { it.id == productId }
        ?.variants
        .orEmpty()
        .map { variant ->
            PurchaseVariant(variant.id, productId, variant.name, variant.sku, variant.purchasePrice, variant.stock)
        }

    override fun createPurchase(items: List<PurchaseDraftItem>, invoice: String, supplier: String, ruc: String, date: String, discount: Double, notes: String): Boolean {
        if (items.isEmpty()) return false
        val gross = items.sumOf { it.quantity * it.unitCost }.roundMoney()
        val safeDiscount = discount.coerceIn(0.0, gross).roundMoney()
        val total = (gross - safeDiscount).roundMoney()
        val records = readRecords().toMutableList()
        records += LocalPurchaseRecord(
            id = UUID.randomUUID().toString(),
            supplierName = supplier.trim(),
            supplierRuc = ruc.trim(),
            invoiceNumber = invoice.trim(),
            purchaseDate = date.ifBlank { today() },
            status = "pending",
            notes = notes.trim(),
            total = total,
            discount = safeDiscount,
            createdAt = System.currentTimeMillis(),
            items = items.map { LocalPurchaseItem(it.productId, it.productName, it.sku, it.quantity, it.unitCost, it.variantId, it.variantLabel) }
        )
        saveRecords(records)
        return true
    }

    override fun receivePurchase(id: String): Boolean {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.id == id && it.status == "pending" }
        if (index < 0) return false
        val record = records[index]
        val itemsWithPreviousStock = record.items.map { item ->
            val product = catalog.getAll().firstOrNull { it.id == item.productId }
            val previousStock = if (!item.variantId.isNullOrBlank()) {
                product?.variants?.firstOrNull { it.id == item.variantId }?.stock
            } else {
                product?.stock
            }
            item.copy(previousStock = previousStock)
        }
        itemsWithPreviousStock.forEach { item ->
            val product = catalog.getAll().firstOrNull { it.id == item.productId } ?: return@forEach
            if (!item.variantId.isNullOrBlank()) {
                val variantIndex = product.variants.indexOfFirst { it.id == item.variantId }
                if (variantIndex < 0) return@forEach
                val variant = product.variants[variantIndex]
                val nextStock = variant.stock + item.quantity
                val nextCost = if (nextStock <= 0.0) item.unitCost else ((variant.stock * variant.purchasePrice) + (item.quantity * item.unitCost)) / nextStock
                catalog.save(product.copy(variants = product.variants.toMutableList().apply {
                    this[variantIndex] = variant.copy(stock = nextStock, purchasePrice = nextCost.roundMoney())
                }))
            } else {
                val nextStock = product.stock + item.quantity
                val nextCost = if (nextStock <= 0.0) item.unitCost else ((product.stock * product.purchasePrice) + (item.quantity * item.unitCost)) / nextStock
                catalog.save(product.copy(stock = nextStock, purchasePrice = nextCost.roundMoney()))
            }
        }
        records[index] = record.copy(status = "received", receivedAt = System.currentTimeMillis(), items = itemsWithPreviousStock)
        saveRecords(records)
        return true
    }

    override fun cancelPurchase(id: String): Boolean {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.id == id && it.status == "pending" }
        if (index < 0) return false
        records[index] = records[index].copy(status = "cancelled")
        saveRecords(records)
        return true
    }

    private fun readRecords(): List<LocalPurchaseRecord> = preferences.getString(KEY_PURCHASES, null)?.let { json ->
        runCatching { gson.fromJson<List<LocalPurchaseRecord>>(json, object : TypeToken<List<LocalPurchaseRecord>>() {}.type) }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun saveRecords(records: List<LocalPurchaseRecord>) { preferences.edit().putString(KEY_PURCHASES, gson.toJson(records)).apply() }
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun Double.roundMoney() = round(this * 100.0) / 100.0

    private data class LocalPurchaseRecord(val id: String, val supplierName: String, val supplierRuc: String, val invoiceNumber: String, val purchaseDate: String, val status: String, val notes: String, val total: Double, val discount: Double, val createdAt: Long, val items: List<LocalPurchaseItem>, val receivedAt: Long? = null) {
        fun toPurchase() = Purchase(id, purchaseDate, supplierName, invoiceNumber, total, status, notes)
    }
    private data class LocalPurchaseItem(val productId: String, val productName: String, val sku: String, val quantity: Double, val unitCost: Double, val variantId: String? = null, val variantLabel: String = "", val previousStock: Double? = null)
    private companion object { const val KEY_PURCHASES = "purchases" }
}

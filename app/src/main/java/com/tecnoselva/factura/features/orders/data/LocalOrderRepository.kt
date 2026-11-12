package com.tecnoselva.factura.features.orders.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tecnoselva.factura.features.orders.model.OrderLineSummary
import com.tecnoselva.factura.features.orders.model.OrderSummary
import com.tecnoselva.factura.features.pos.model.PosCartItem
import com.tecnoselva.factura.features.pos.model.PosCheckout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LocalOrderRepository(context: Context, private val localStoreId: String) {
    private val preferences = context.getSharedPreferences("local_orders_$localStoreId", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getOrders(search: String? = null): List<OrderSummary> = readRecords()
        .sortedByDescending { it.createdAt }
        .filter { record ->
            val query = search?.trim().orEmpty()
            query.isBlank() || listOf(record.customerName, record.customerDocumentType, record.customerDocumentNumber, record.orderNumber)
                .any { it.contains(query, ignoreCase = true) }
        }
        .map { record ->
            OrderSummary(
                id = record.id,
                orderNumber = record.orderNumber,
                customerName = record.customerName,
                total = record.total,
                status = record.status,
                paymentStatus = record.paymentStatus,
                documentType = "nota_venta",
                createdAt = record.createdAt.toString(),
                itemsSummary = record.items.joinToString(" · ") {
                    "${it.quantity}x ${it.label}${it.variantLabel.takeIf { label -> label.isNotBlank() }?.let { label -> " · $label" }.orEmpty()}"
                },
                items = record.items.map { item ->
                    OrderLineSummary(item.label, item.quantity.toDouble(), item.unitPrice, item.subtotal, item.variantLabel)
                },
                customerDocumentType = record.customerDocumentType,
                customerDocumentNumber = record.customerDocumentNumber,
                paymentMethod = record.paymentMethod,
                amountPaid = record.amountPaid,
                amountPending = (record.total - record.amountPaid).coerceAtLeast(0.0),
                subtotal = record.total,
                deliveredAt = record.createdAt.toString()
            )
        }

    fun recordSale(cart: List<PosCartItem>, checkout: PosCheckout) {
        val now = System.currentTimeMillis()
        val records = readRecords().toMutableList()
        val sequence = records.size + 1
        val number = "PED-${SimpleDateFormat("yyyy", Locale.US).format(Date(now))}-${sequence.toString().padStart(5, '0')}"
        val total = cart.sumOf { it.subtotal }
        records += LocalOrderRecord(
            id = UUID.randomUUID().toString(),
            orderNumber = number,
            customerName = checkout.customerName,
            total = total,
            status = "delivered",
            paymentStatus = when {
                checkout.paymentMode.name == "CREDIT" -> "pending"
                checkout.amountPaid >= total -> "paid"
                checkout.amountPaid > 0 -> "partial"
                else -> "pending"
            },
            createdAt = now,
            paymentMethod = checkout.paymentMethod.apiValue,
            amountPaid = checkout.amountPaid,
            customerDocumentType = checkout.customerDocumentType.apiValue,
            customerDocumentNumber = checkout.customerDocumentNumber,
            items = cart.map {
                LocalOrderItem(
                    label = it.name,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    subtotal = it.subtotal,
                    variantLabel = it.variantLabel.orEmpty()
                )
            }
        )
        preferences.edit().putString(KEY_ORDERS, gson.toJson(records)).apply()
    }

    private fun readRecords(): List<LocalOrderRecord> {
        val json = preferences.getString(KEY_ORDERS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<LocalOrderRecord>>(json, object : TypeToken<List<LocalOrderRecord>>() {}.type)
        }.getOrDefault(emptyList())
    }

    private data class LocalOrderRecord(
        val id: String,
        val orderNumber: String,
        val customerName: String,
        val total: Double,
        val status: String,
        val paymentStatus: String,
        val createdAt: Long,
        val items: List<LocalOrderItem>,
        val paymentMethod: String = "",
        val amountPaid: Double = 0.0,
        val customerDocumentType: String = "",
        val customerDocumentNumber: String = ""
    )

    private data class LocalOrderItem(
        val label: String,
        val quantity: Int,
        val unitPrice: Double = 0.0,
        val subtotal: Double = 0.0,
        val variantLabel: String = ""
    )

    private companion object { const val KEY_ORDERS = "orders" }
}

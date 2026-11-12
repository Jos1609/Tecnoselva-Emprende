package com.tecnoselva.factura.features.pos.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.pos.model.PosCartItem
import com.tecnoselva.factura.features.pos.model.PosCheckout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sends the same /print-jobs contract used by the AdminStore web application. */
class PosPrintAgentService {
    fun printSale(
        storeId: String,
        orderId: String,
        orderNumber: String,
        cart: List<PosCartItem>,
        checkout: PosCheckout
    ): String? {
        val store = loadStorePrintConfiguration(storeId)
        // El agente es una integración opcional por tienda.
        if (store.agentUrl.isBlank()) return null
        val agentUrl = normalizeAgentUrl(store.agentUrl)
        val rawText = buildTicket(store, orderNumber, cart, checkout)
        val endpoint = if (agentUrl.endsWith("/print-jobs", ignoreCase = true)) agentUrl else "$agentUrl/print-jobs"
        val payload = JsonObject().apply {
            addProperty("source", "admin-store-android-pos")
            add("jobs", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("station", "counter")
                    addProperty("jobType", "document")
                    addProperty("documentType", "web-order")
                    addProperty("storeId", storeId)
                    addProperty("orderId", orderId)
                    addProperty("documentNumber", orderNumber)
                    addProperty("format", "thermal-80")
                    addProperty("firedAt", isoNow())
                    addProperty("rawText", rawText)
                })
            })
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val status = connection.responseCode
        val body = try {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val response = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val firstResult = response?.getAsJsonArray("results")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val agentSucceeded = status in 200..299 && response?.get("success")?.asBoolean == true && firstResult?.get("ok")?.asBoolean != false
        if (!agentSucceeded) {
            val detail = firstResult?.text("error") ?: response?.text("error") ?: "El agente respondió HTTP $status"
            throw IllegalStateException(detail)
        }
        return firstResult?.text("printerName")?.takeIf { it.isNotBlank() } ?: "impresora configurada"
    }

    private fun loadStorePrintConfiguration(storeId: String): StorePrintConfiguration {
        val rows = SupabaseService.getRest(
            "stores?id=eq.$storeId&select=name,razon_social,ruc,address,phone,settings&limit=1"
        ).asJsonArray
        val row = rows.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("No se encontró la configuración de la tienda.")
        val settings = row.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
        val agentUrl = settings?.text("print_agent_url").orEmpty()
        return StorePrintConfiguration(
            name = row.text("name").ifBlank { "TecnoSelva" },
            legalName = row.text("razon_social"),
            ruc = row.text("ruc"),
            address = row.text("address"),
            phone = row.text("phone"),
            agentUrl = agentUrl
        )
    }

    private fun buildTicket(
        store: StorePrintConfiguration,
        orderNumber: String,
        cart: List<PosCartItem>,
        checkout: PosCheckout
    ): String {
        val total = cart.sumOf { it.subtotal }
        val subtotal = if (checkout.includeIgv) total / 1.18 else total
        val tax = total - subtotal
        val lines = mutableListOf<String>()
        lines += center(store.name.uppercase())
        if (store.legalName.isNotBlank() && !store.legalName.equals(store.name, true)) lines += center(store.legalName.uppercase())
        if (store.ruc.isNotBlank()) lines += center("RUC: ${store.ruc}")
        if (store.address.isNotBlank()) lines += wrap(store.address)
        if (store.phone.isNotBlank()) lines += center("Tel: ${store.phone}")
        lines += divider('=')
        lines += center("RESUMEN DE COMPRA")
        lines += center("#${orderNumber.ifBlank { "VENTA POS" }}")
        lines += center(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "PE")).format(Date()))
        lines += divider('=')
        lines += wrap("Cliente: ${checkout.customerName}")
        if (checkout.customerDocumentNumber.isNotBlank()) lines += "${checkout.customerDocumentType.label}: ${checkout.customerDocumentNumber}"
        lines += divider('-')
        cart.forEach { item ->
            val description = item.name + (item.variantLabel?.let { " ($it)" } ?: "")
            lines += wrap("${item.quantity} x $description")
            lines += spaced("  ${money(item.unitPrice)} c/u", money(item.subtotal))
        }
        lines += divider('-')
        lines += spaced("Subtotal:", money(subtotal))
        lines += spaced(if (checkout.includeIgv) "IGV (18%):" else "IGV exonerado:", money(tax))
        lines += divider('=')
        lines += center("TOTAL: ${money(total)}")
        lines += divider('=')
        lines += spaced("Pago:", checkout.paymentMethod.label)
        lines += spaced("Estado:", checkout.paymentMode.label)
        if (checkout.amountPaid > 0) lines += spaced("Pagado:", money(checkout.amountPaid))
        val pending = (total - checkout.amountPaid).coerceAtLeast(0.0)
        if (pending > 0.009) lines += spaced("Pendiente:", money(pending))
        lines += divider('-')
        lines += center("GRACIAS POR TU COMPRA")
        lines += "\n\n"
        return lines.joinToString("\n")
    }

    private fun normalizeAgentUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            "La URL del agente debe comenzar con http:// o https://."
        }
        return normalized
    }

    private fun wrap(text: String): List<String> {
        val result = mutableListOf<String>()
        var line = ""
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
            if (line.isEmpty() || "$line $word".length <= WIDTH) line += if (line.isEmpty()) word else " $word"
            else { result += line; line = word }
        }
        if (line.isNotEmpty()) result += line
        return result.ifEmpty { listOf("") }
    }
    private fun center(value: String): String { val text = value.take(WIDTH); return " ".repeat(((WIDTH - text.length) / 2).coerceAtLeast(0)) + text }
    private fun divider(character: Char) = character.toString().repeat(WIDTH)
    private fun spaced(label: String, value: String): String = label + " ".repeat((WIDTH - label.length - value.length).coerceAtLeast(1)) + value
    private fun money(value: Double) = "S/ " + String.format(Locale.US, "%.2f", value)
    private fun isoNow() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
    private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private data class StorePrintConfiguration(
        val name: String,
        val legalName: String,
        val ruc: String,
        val address: String,
        val phone: String,
        val agentUrl: String
    )

    private companion object { const val WIDTH = 48 }
}

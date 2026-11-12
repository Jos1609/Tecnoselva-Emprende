package com.tecnoselva.factura.core.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import android.util.Log
import com.tecnoselva.factura.features.documents.model.Invoice
import com.tecnoselva.factura.features.documents.model.InvoiceDetailLine
import com.tecnoselva.factura.features.documents.model.InvoiceLegend
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.auth.model.CustomerLookupResult
import com.tecnoselva.factura.features.auth.model.LoginResult
import com.tecnoselva.factura.features.billing.model.OperationResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SupabaseConfig {
    const val URL = "https://ryonvdnorswiyrzgyxlz.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJ5b252ZG5vcnN3aXlyemd5eGx6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM5Mzc3MzQsImV4cCI6MjA4OTUxMzczNH0.4S-sZcvCX7Pj4tktnVAjipoivVQzTCdfQhRzgs1oSpU"
}

object SupabaseService {
    private const val TAG = "BillingFlow"

    private fun failure(message: String, error: Throwable? = null): OperationResult {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        return OperationResult(false, message)
    }

    fun login(email: String, pass: String): LoginResult {
        try {
            val url = URL("${SupabaseConfig.URL}/auth/v1/token?grant_type=password")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", pass)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode != 200) {
                return LoginResult(false, "Credenciales incorrectas o error de autenticación", null, null, null, null, false)
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JsonParser.parseString(response).asJsonObject
            val accessToken = json.get("access_token")?.asString ?: return LoginResult(false, "Token no recibido", null, null, null, null, false)
            val userObj = json.getAsJsonObject("user")
            val userId = userObj?.get("id")?.asString ?: return LoginResult(false, "Usuario no encontrado", null, null, null, null, false)
            val userEmail = userObj.get("email")?.asString ?: email

            val storeResult = getStoreInfo(userId, userEmail, accessToken)
            if (storeResult.storeId.isNullOrBlank()) {
                return LoginResult(false, "La cuenta autenticó, pero AdminStore no devolvió una tienda asociada (owner/empleado).", userId, userEmail, null, null, false)
            }

            val issuer = fetchIssuerDetails(storeResult.storeId, accessToken)
            return LoginResult(
                success = true,
                error = null,
                userId = userId,
                email = userEmail,
                storeId = storeResult.storeId,
                businessName = storeResult.businessName,
                isSubscriptionActive = storeResult.isSubscriptionActive,
                accessToken = accessToken,
                refreshToken = json.get("refresh_token")?.asString,
                issuerRuc = issuer["ruc"],
                issuerLegalName = issuer["legalName"],
                issuerAddress = issuer["address"],
                issuerCity = issuer["city"],
                issuerLogoUrl = issuer["logoUrl"],
                issuerSlogan = issuer["slogan"],
                issuerPhone = issuer["phone"]
            )
        } catch (e: Exception) {
            return LoginResult(false, "Error de conexión: ${e.localizedMessage}", null, null, null, null, false)
        }
    }

    data class StoreCheckResult(val storeId: String?, val businessName: String?, val isSubscriptionActive: Boolean)

    private fun fetchIssuerDetails(storeId: String?, token: String): Map<String, String> {
        if (storeId.isNullOrBlank()) return emptyMap()
        return runCatching {
            fun firstRow(path: String) = runCatching {
                val url = URL("${SupabaseConfig.URL}/rest/v1/$path")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (connection.responseCode !in 200..299) return@runCatching null
                JsonParser.parseString(BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() })
                    .asJsonArray.firstOrNull()?.asJsonObject
            }.getOrNull()
            fun value(row: JsonObject?, key: String) = row?.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

            val store = firstRow("stores?id=eq.$storeId&select=name,slogan,logo_url,phone,ruc,razon_social,address,city,state&limit=1")
            val sunat = firstRow("sunat_configuration?store_id=eq.$storeId&is_active=eq.true&select=ruc,razon_social,direccion,departamento,provincia,distrito&limit=1")
            val city = listOf(value(sunat, "distrito"), value(sunat, "provincia"), value(sunat, "departamento"))
                .filter { it.isNotBlank() }.joinToString(", ").ifBlank {
                    listOf(value(store, "city"), value(store, "state")).filter { it.isNotBlank() }.joinToString(", ")
            }
            mapOf(
                "ruc" to value(sunat, "ruc").ifBlank { value(store, "ruc") },
                "legalName" to value(sunat, "razon_social").ifBlank { value(store, "razon_social") },
                "address" to value(sunat, "direccion").ifBlank { value(store, "address") },
                "city" to city,
                "logoUrl" to value(store, "logo_url"),
                "slogan" to value(store, "slogan"),
                "phone" to value(store, "phone")
            )
        }.getOrDefault(emptyMap())
    }

    fun fetchIssuerDetails(storeId: String): Map<String, String> = fetchIssuerDetails(storeId, accessToken)

    private fun getStoreInfo(userId: String, email: String, accessToken: String): StoreCheckResult {
        try {
            val url = URL("${SupabaseConfig.URL}/rest/v1/stores?owner_id=eq.$userId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $accessToken")

            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val array = JsonParser.parseString(response).asJsonArray
                if (array.size() > 0) {
                    val storeObj = array.get(0).asJsonObject
                    val storeId = storeObj.get("id")?.asString
                    val name = storeObj.get("name")?.asString ?: "Mi Tienda"
                    val status = storeObj.get("status")?.asString ?: "activo"
                    val nextPaymentDateStr = storeObj.get("next_payment_date")?.asString

                    var subscriptionActive = (status == "activo")
                    if (nextPaymentDateStr != null) {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val paymentDate = sdf.parse(nextPaymentDateStr)
                            val today = Date()
                            if (paymentDate != null && today.after(paymentDate)) {
                                subscriptionActive = false
                            }
                        } catch (_: Exception) {}
                    }

                    return StoreCheckResult(storeId, name, subscriptionActive)
                }
            }
        } catch (_: Exception) {}

        // AdminStore también autentica empleados; sus tiendas se resuelven por store_employees.
        // El flujo web prioriza user_id y luego usa email para cuentas antiguas sin rol asignado.
        val employeeFilters = listOf(
            "user_id=eq.$userId&status=eq.active&active=eq.true",
            "email=eq.${java.net.URLEncoder.encode(email, "UTF-8")}&status=eq.active&active=eq.true"
        )
        for (filter in employeeFilters) {
            try {
                val employeeUrl = URL("${SupabaseConfig.URL}/rest/v1/store_employees?$filter&select=store_id,full_name&order=created_at.asc&limit=1")
                val employeeConn = employeeUrl.openConnection() as HttpURLConnection
                employeeConn.requestMethod = "GET"
                employeeConn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                employeeConn.setRequestProperty("Authorization", "Bearer $accessToken")
                employeeConn.connectTimeout = 10000
                employeeConn.readTimeout = 15000
                if (employeeConn.responseCode !in 200..299) continue
                val employees = JsonParser.parseString(BufferedReader(InputStreamReader(employeeConn.inputStream)).use { it.readText() }).asJsonArray
                if (employees.size() == 0) continue
                val employee = employees[0].asJsonObject
                val storeId = employee.get("store_id")?.asString ?: continue

                val checkUrl = URL("${SupabaseConfig.URL}/rest/v1/rpc/check_store_for_employee")
                val checkConn = checkUrl.openConnection() as HttpURLConnection
                checkConn.requestMethod = "POST"
                checkConn.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                checkConn.setRequestProperty("Authorization", "Bearer $accessToken")
                checkConn.setRequestProperty("Content-Type", "application/json")
                checkConn.doOutput = true
                OutputStreamWriter(checkConn.outputStream).use {
                    it.write(JsonObject().apply { addProperty("p_store_id", storeId) }.toString())
                }
                if (checkConn.responseCode !in 200..299) continue
                val check = JsonParser.parseString(BufferedReader(InputStreamReader(checkConn.inputStream)).use { it.readText() }).asJsonObject
                val active = check.get("is_valid")?.asBoolean ?: false
                val employeeName = employee.get("full_name")?.asString?.takeIf { it.isNotBlank() } ?: "Mi Tienda"
                return StoreCheckResult(storeId, employeeName, active)
            } catch (_: Exception) { }
        }
        return StoreCheckResult(null, null, false)
    }

    @Volatile private var accessToken: String = ""
    fun setAccessToken(token: String) { accessToken = token }

    private fun authenticatedConnection(url: URL, method: String = "GET"): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            if (accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15000
            readTimeout = 30000
        }
    }

    /** Shared authenticated RPC transport used by native feature modules. */
    fun callRpc(functionName: String, payload: JsonObject): JsonElement {
        val conn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/rpc/$functionName"), "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
        val status = conn.responseCode
        // Los RPC de catálogo devuelven arreglos grandes. readResponse() está limitado a 2000
        // caracteres para diagnósticos y truncaba el JSON antes de intentar parsearlo.
        val body = try {
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        if (status !in 200..299) {
            Log.e(TAG, "RPC $functionName · HTTP $status: ${body.take(1000)}")
            // PostgREST uses PGRST202 when the function exists but the supplied
            // parameter list does not match its published signature. Preserve
            // that code so callers can apply a backwards-compatible payload.
            val errorCode = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .get("code")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
            }.getOrNull()
            val codeSuffix = errorCode?.let { " · $it" }.orEmpty()
            throw IllegalStateException("No se pudo completar $functionName (HTTP $status$codeSuffix).")
        }
        return runCatching { JsonParser.parseString(body) }
            .getOrElse {
                Log.e(TAG, "RPC $functionName devolvió JSON inválido (${body.length} caracteres)", it)
                throw IllegalStateException("$functionName devolvió una respuesta que no se pudo procesar.", it)
            }
    }

    /** Performs a full authenticated PostgREST read without truncating the response body. */
    fun getRest(pathAndQuery: String): JsonElement {
        val normalizedPath = pathAndQuery.trimStart('/')
        val conn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/$normalizedPath"))
        conn.setRequestProperty("Accept", "application/json")
        val status = conn.responseCode
        val body = try {
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        if (status !in 200..299) {
            Log.e(TAG, "GET $normalizedPath · HTTP $status: ${body.take(1000)}")
            throw IllegalStateException("No se pudo leer la configuración de la tienda (HTTP $status).")
        }
        return runCatching { JsonParser.parseString(body) }
            .getOrElse { throw IllegalStateException("Supabase devolvió una configuración inválida.", it) }
    }

    fun lookupCustomer(storeId: String, docType: String, docNumber: String): CustomerLookupResult {
        try {
            val urlCustomers = URL("${SupabaseConfig.URL}/rest/v1/customers?store_id=eq.$storeId&document_number=eq.$docNumber&limit=1")
            val connC = authenticatedConnection(urlCustomers)

            if (connC.responseCode == 200) {
                val respC = BufferedReader(InputStreamReader(connC.inputStream)).use { it.readText() }
                val arrayC = JsonParser.parseString(respC).asJsonArray
                if (arrayC.size() > 0) {
                    val objC = arrayC.get(0).asJsonObject
                    val name = objC.get("name")?.asString ?: ""
                    val address = objC.get("address")?.asString ?: ""
                    if (name.isNotEmpty()) {
                        return CustomerLookupResult(true, name, address, null)
                    }
                }
            }

            val directUrls = if (docType == "1") {
                listOf(
                    "https://api.apis.net.pe/v2/reniec/dni/$docNumber",
                    "https://dniruc.apisperu.com/api/v1/dni/$docNumber"
                )
            } else {
                listOf(
                    "https://api.sunat.cloud/ruc/$docNumber",
                    "https://api.apis.net.pe/v2/sunat/ruc/$docNumber",
                    "https://dniruc.apisperu.com/api/v1/ruc/$docNumber"
                )
            }

            for (apiUrl in directUrls) {
                try {
                    val urlU = URL(apiUrl)
                    val connU = urlU.openConnection() as HttpURLConnection
                    connU.requestMethod = "GET"
                    connU.setRequestProperty("Accept", "application/json")
                    connU.connectTimeout = 5000
                    connU.readTimeout = 5000

                    if (connU.responseCode == 200) {
                        val respU = BufferedReader(InputStreamReader(connU.inputStream)).use { it.readText() }
                        val jsonU = JsonParser.parseString(respU).asJsonObject
                        val name = if (docType == "1") {
                            val nombres = jsonU.get("nombres")?.asString ?: ""
                            val pat = jsonU.get("apellidoPaterno")?.asString ?: ""
                            val mat = jsonU.get("apellidoMaterno")?.asString ?: ""
                            "$pat $mat $nombres".trim()
                        } else {
                            jsonU.get("razonSocial")?.asString ?: jsonU.get("nombre_o_razon_social")?.asString ?: jsonU.get("nombre")?.asString ?: ""
                        }
                        val address = jsonU.get("direccion")?.asString ?: jsonU.get("direccion_completa")?.asString ?: ""

                        if (name.isNotEmpty()) {
                            return CustomerLookupResult(true, name, address, null)
                        }
                    }
                } catch (_: Exception) {}
            }

            val urlEf = URL("${SupabaseConfig.URL}/functions/v1/customer-lookup")
            val connEf = urlEf.openConnection() as HttpURLConnection
            connEf.requestMethod = "POST"
            connEf.setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            connEf.setRequestProperty("Authorization", "Bearer ${if (accessToken.isBlank()) SupabaseConfig.ANON_KEY else accessToken}")
            connEf.setRequestProperty("Content-Type", "application/json")
            connEf.doOutput = true

            val reqBody = JsonObject().apply {
                addProperty("documentType", docType)
                addProperty("documentNumber", docNumber)
            }
            OutputStreamWriter(connEf.outputStream).use { it.write(reqBody.toString()) }

            if (connEf.responseCode == 200) {
                val respEf = BufferedReader(InputStreamReader(connEf.inputStream)).use { it.readText() }
                val jsonEf = JsonParser.parseString(respEf).asJsonObject
                val success = jsonEf.get("success")?.asBoolean ?: false
                if (success) {
                    val dataObj = jsonEf.getAsJsonObject("data")
                    val name = dataObj?.get("name")?.asString ?: ""
                    val address = dataObj?.get("address")?.asString ?: ""
                    if (name.isNotEmpty()) {
                        return CustomerLookupResult(true, name, address, null)
                    }
                }
            }
        } catch (e: Exception) {
            return CustomerLookupResult(false, null, null, e.localizedMessage)
        }

        return CustomerLookupResult(false, null, null, "No se encontró el documento en SUNAT/RENIEC.")
    }

    fun fetchInvoices(storeId: String, search: String? = null, orderId: String? = null): List<Invoice> {
        val invoices = mutableListOf<Invoice>()
        try {
            require(storeId.isNotBlank()) { "No hay una tienda seleccionada en la sesión." }
            val pageSize = 20
            val allRows = mutableListOf<JsonObject>()
            var offset = 0
            while (true) {
                val clauses = mutableListOf("store_id=eq.$storeId")
                if (!orderId.isNullOrBlank()) {
                    clauses += "order_id=eq.$orderId"
                } else if (search.isNullOrBlank()) {
                    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale("es", "PE"))
                    formatter.timeZone = java.util.TimeZone.getTimeZone("America/Lima")
                    val today = formatter.format(Date())
                    clauses += "or=(issue_date.gte.$today,created_at.gte.${today}T00:00:00-05:00)"
                } else {
                    val safeSearch = search.trim().replace(Regex("[,()]"), "")
                    if (safeSearch.isNotBlank()) {
                        val pattern = java.net.URLEncoder.encode("%$safeSearch%", "UTF-8")
                        clauses += "or=(full_number.ilike.$pattern,customer_name.ilike.$pattern,customer_doc_number.ilike.$pattern)"
                    }
                }
                clauses += "select=*"
                clauses += "order=created_at.desc"
                clauses += "limit=$pageSize"
                clauses += "offset=$offset"
                val url = URL("${SupabaseConfig.URL}/rest/v1/electronic_documents?${clauses.joinToString("&")}")
            val conn = authenticatedConnection(url)

            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val parsed = try {
                    JsonParser.parseString(response)
                } catch (e: Exception) {
                    throw IllegalStateException("Supabase devolvió una respuesta que no es JSON (HTTP ${conn.responseCode}): ${response.take(500)}", e)
                }
                if (parsed.isJsonNull) {
                    throw IllegalStateException("Supabase devolvió JSON null al consultar electronic_documents (tienda $storeId).")
                }
                if (!parsed.isJsonArray) {
                    val detail = if (parsed.isJsonObject) {
                        val obj = parsed.asJsonObject
                        listOf("code", "message", "details", "hint")
                            .mapNotNull { key -> obj.get(key)?.takeUnless { it.isJsonNull }?.let { "$key=$it" } }
                            .joinToString("; ").ifBlank { parsed.toString() }
                    } else parsed.toString()
                    throw IllegalStateException("Respuesta inesperada de electronic_documents: ${detail.take(700)}")
                }
                val array = parsed.asJsonArray
                for (elem in array) {
                    if (!elem.isJsonObject) continue
                    val row = elem.asJsonObject
                    val active = row.get("active")
                    if (active == null || active.isJsonNull || runCatching { active.asBoolean }.getOrDefault(true)) {
                        allRows.add(row)
                    }
                }
                if (array.size() < pageSize) break
                offset += pageSize
            } else {
                throw IllegalStateException("HTTP ${conn.responseCode}: ${readError(conn)}")
            }
            }

            for (obj in allRows) {
                fun string(vararg keys: String, default: String = ""): String = keys.firstNotNullOfOrNull { key ->
                    obj.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
                        ?.takeIf { it.isNotBlank() }
                } ?: default
                fun number(vararg keys: String): Double = keys.firstNotNullOfOrNull { key ->
                    obj.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
                } ?: 0.0

                val docType = string("document_type", default = "03").padStart(2, '0')
                val serie = string("serie", "series")
                val correlative = string("correlative", "number")
                val fullNumber = string("full_number", default = listOf(serie, correlative).filter { it.isNotBlank() }.joinToString("-"))
                val id = fullNumber.ifBlank { string("id", default = "Comprobante") }
                val label = when (docType) {
                    "01" -> "Factura"
                    "03" -> "Boleta"
                    "07" -> "Nota de crédito"
                    "08" -> "Nota de débito"
                    else -> "Comprobante ($docType)"
                }
                val igvAmount = number("igv_amount", "tax_amount", "igv")
                val lineItems = sequenceOf("line_items", "items", "details")
                    .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
                    .firstOrNull()
                    ?.mapNotNull { element ->
                        if (!element.isJsonObject) return@mapNotNull null
                        val line = element.asJsonObject
                        fun lineText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
                            line.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
                                ?.takeIf { it.isNotBlank() }
                        }.orEmpty()
                        fun lineNumber(vararg keys: String): Double = keys.firstNotNullOfOrNull { key ->
                            line.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
                        } ?: 0.0
                        val description = lineText("description", "name", "item_name")
                        val quantity = lineNumber("quantity", "qty")
                        val unitPrice = lineNumber("unit_price", "unitPrice", "price")
                        val taxed = line.get("taxed")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: (igvAmount > 0)
                        if (description.isBlank() || quantity <= 0.0) null
                        else InvoiceDetailLine(description, quantity, unitPrice, lineText("item_type", "unit_code").ifBlank { "bien" }, taxed)
                    }
                    .orEmpty()
                val legends = obj.get("legends")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { element ->
                        if (!element.isJsonObject) return@mapNotNull null
                        val legend = element.asJsonObject
                        val value = legend.get("value")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()
                        if (value.isBlank()) null else InvoiceLegend(
                            code = legend.get("code")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty(),
                            value = value
                        )
                    }.orEmpty()
                val totalAmount = number("total_amount", "total").takeIf { it > 0 }
                    ?: lineItems.sumOf { it.quantity * it.unitPrice }
                val computedSubtotal = lineItems.sumOf { line ->
                    line.quantity * if (line.taxed) line.unitPrice / 1.18 else line.unitPrice
                }
                val subtotal = sequenceOf("subtotal", "line_extension_amount", "taxable_amount", "exonerated_amount")
                    .mapNotNull { key -> obj.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } }
                    .firstOrNull { it > 0.0 } ?: computedSubtotal
                invoices.add(Invoice(
                    id = id,
                    clientName = string("customer_name", default = "Cliente"),
                    clientRuc = string("customer_doc_number", default = "—"),
                    description = "$label electrónica SUNAT",
                    quantity = 1,
                    unitPrice = number("subtotal"),
                    totalAmount = totalAmount,
                    date = string("issue_date", "created_at", "updated_at"),
                    status = string("status", default = "pending").lowercase(Locale.ROOT),
                    documentType = docType,
                    documentUuid = string("id"),
                    subtotal = subtotal,
                    igvAmount = igvAmount,
                    customerAddress = string("customer_address"),
                    serie = serie,
                    correlative = correlative,
                    currency = string("currency", default = "PEN"),
                    errorMessage = string("error_message", "cdr_description"),
                    lineItems = lineItems,
                    hash = string("xml_hash", "hash"),
                    qrData = string("qr_code", "qr_data"),
                    notes = string("observations", "notes"),
                    taxRate = number("tax_rate").takeIf { it > 0 } ?: if (igvAmount == 0.0) 0.0 else 18.0,
                    allowanceTotalAmount = number("allowance_total_amount", "discount_amount"),
                    chargeTotalAmount = number("charge_total_amount", "shipping_amount"),
                    payableAmount = number("payable_amount", "total_amount", "total").takeIf { it > 0 } ?: totalAmount,
                    legends = legends
                ))
            }
            invoices.sortByDescending { it.date }
        } catch (e: Exception) {
            android.util.Log.e("BillingFlow", "Error cargando electronic_documents para store_id=$storeId", e)
            throw IllegalStateException("No se pudo cargar la facturación: ${e.localizedMessage}", e)
        }
        return invoices
    }

    fun fetchInvoiceByOrderId(storeId: String, orderId: String): Invoice? =
        fetchInvoices(storeId = storeId, orderId = orderId).firstOrNull()

    sealed class SessionRefreshResult {
        data class Success(val accessToken: String, val refreshToken: String) : SessionRefreshResult()
        data class Rejected(val statusCode: Int, val message: String) : SessionRefreshResult()
        data class Unavailable(val message: String) : SessionRefreshResult()
    }

    /**
     * Renews a Supabase session without attaching the current access token.
     * An expired bearer token on this endpoint can make the gateway reject an otherwise valid
     * refresh token, which is exactly the case this method must recover from.
     */
    fun refreshSession(refreshToken: String): SessionRefreshResult {
        if (refreshToken.isBlank()) return SessionRefreshResult.Rejected(401, "No hay token de renovación")
        return try {
            val conn = (URL("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream).use {
                it.write(JsonObject().apply { addProperty("refresh_token", refreshToken) }.toString())
            }
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                val message = readError(conn)
                Log.w(TAG, "Supabase rechazó la renovación de sesión: HTTP $statusCode $message")
                if (statusCode in 400..499) SessionRefreshResult.Rejected(statusCode, message)
                else SessionRefreshResult.Unavailable("HTTP $statusCode: $message")
            } else {
                val json = JsonParser.parseString(
                    BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                ).asJsonObject
                val access = json.get("access_token")?.asString.orEmpty()
                val refresh = json.get("refresh_token")?.asString.orEmpty()
                if (access.isBlank() || refresh.isBlank()) {
                    SessionRefreshResult.Unavailable("Supabase no devolvió los tokens renovados")
                } else {
                    setAccessToken(access)
                    SessionRefreshResult.Success(access, refresh)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo conectar para renovar la sesión", error)
            SessionRefreshResult.Unavailable(error.localizedMessage ?: "Error de conexión")
        }
    }

    fun fetchReferenceDocuments(storeId: String): List<Invoice> = fetchInvoices(storeId).filter {
        (it.documentType == "01" || it.documentType == "03") && (it.status == "accepted" || it.status == "accepted_with_observations" || it.status == "observed")
    }

    fun createInvoice(storeId: String, documentType: String, customerDocType: String, clientRuc: String, clientName: String, clientAddress: String, lineItems: List<InvoiceLineItem>, reference: JsonObject? = null, observations: String? = null, orderId: String? = null): OperationResult {
        try {
            var taxedBase = 0.0
            var exemptBase = 0.0
            var igv = 0.0
            var total = 0.0

            val jsonArrayItems = JsonArray()
            for (li in lineItems) {
                val lineTotal = li.quantity * li.unitPrice
                val lineSub = lineTotal
                val lineIgv = if (li.taxed) lineSub * 0.18 else 0.0
                if (li.taxed) taxedBase += lineSub else exemptBase += lineSub
                igv += lineIgv
                total += lineSub + lineIgv

                val itemObj = JsonObject().apply {
                    addProperty("description", li.description)
                    addProperty("quantity", li.quantity)
                    addProperty("unit_price", if (li.taxed) li.unitPrice * 1.18 else li.unitPrice)
                    addProperty("item_type", li.itemType)
                    addProperty("taxed", li.taxed)
                }
                jsonArrayItems.add(itemObj)
            }

            val subtotal = taxedBase + exemptBase
            val seriesKey = when (documentType) {
                "01" -> "factura_serie"
                "03" -> "boleta_serie"
                "07" -> "nota_credito_serie"
                else -> "nota_debito_serie"
            }
            val defaultSerie = when (documentType) {
                "01" -> "F001"
                "03" -> "B001"
                "07" -> if (reference?.get("type")?.asString == "01") "FC01" else "BC01"
                else -> if (reference?.get("type")?.asString == "01") "FD01" else "BD01"
            }
            val configConn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/sunat_configuration?store_id=eq.$storeId&is_active=eq.true&select=$seriesKey&limit=1"))
            if (configConn.responseCode !in 200..299) return failure("No se pudo leer la serie SUNAT: ${readError(configConn)}")
            val configRows = JsonParser.parseString(BufferedReader(InputStreamReader(configConn.inputStream)).use { it.readText() }).asJsonArray
            if (configRows.size() == 0) return failure("No hay configuración SUNAT activa para la tienda")
            var configuredSerie = configRows[0].asJsonObject.get(seriesKey)?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: defaultSerie
            val referenceType = reference?.get("type")?.asString
            if (documentType == "07" || documentType == "08") {
                if (referenceType == "01" && !configuredSerie.startsWith("F")) configuredSerie = if (documentType == "07") "FC01" else "FD01"
                if (referenceType == "03" && !configuredSerie.startsWith("B")) configuredSerie = if (documentType == "07") "BC01" else "BD01"
            }

            val numberConn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/rpc/get_next_electronic_number"), "POST")
            numberConn.setRequestProperty("Content-Type", "application/json")
            numberConn.doOutput = true
            OutputStreamWriter(numberConn.outputStream).use {
                it.write(JsonObject().apply {
                    addProperty("p_store_id", storeId)
                    addProperty("p_document_type", documentType)
                    addProperty("p_serie", configuredSerie)
                }.toString())
            }
            if (numberConn.responseCode !in 200..299) return failure("No se pudo reservar serie/correlativo: ${readError(numberConn)}")
            val numberResponse = JsonParser.parseString(BufferedReader(InputStreamReader(numberConn.inputStream)).use { it.readText() })
            val numberRow = when {
                numberResponse.isJsonArray && numberResponse.asJsonArray.size() > 0 -> numberResponse.asJsonArray[0].asJsonObject
                numberResponse.isJsonObject -> numberResponse.asJsonObject
                else -> return failure("RPC get_next_electronic_number respondió inválidamente: $numberResponse")
            }
            val serie = numberRow.get("out_serie")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                ?: return failure("La reserva SUNAT no devolvió una serie válida: $numberResponse")
            val correlative = numberRow.get("out_correlative")?.asInt
                ?: return failure("No llegó out_correlative al reservar el número: $numberResponse")
            if (correlative <= 0) return failure("La reserva SUNAT devolvió un correlativo inválido ($correlative): $numberResponse")
            val fullNumber = numberRow.get("out_full_number")?.asString
                ?: "$serie-${correlative.toString().padStart(8, '0')}"
            val expectedFullNumber = "$serie-${correlative.toString().padStart(8, '0')}"
            if (fullNumber != expectedFullNumber) {
                return failure("La reserva SUNAT devolvió un número inconsistente. Serie/correlativo=$expectedFullNumber, número=$fullNumber")
            }

            val createConn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/rpc/create_electronic_document"), "POST")
            createConn.setRequestProperty("Content-Type", "application/json"); createConn.doOutput = true
            val refSeries = reference?.get("series")?.asString
            val refNumber = reference?.get("number")?.asString?.toIntOrNull()
            val refFullNumber = if (refSeries != null && refNumber != null) "$refSeries-${refNumber.toString().padStart(8, '0')}" else null
            val documentData = JsonObject().apply {
                if (orderId.isNullOrBlank()) add("order_id", com.google.gson.JsonNull.INSTANCE) else addProperty("order_id", orderId)
                addProperty("document_type", documentType)
                addProperty("serie", serie); addProperty("correlative", correlative); addProperty("full_number", fullNumber)
                addProperty("customer_doc_type", customerDocType); addProperty("customer_doc_number", clientRuc)
                addProperty("customer_name", clientName); addProperty("customer_address", clientAddress)
                addProperty("currency", "PEN"); addProperty("subtotal", subtotal); addProperty("igv_amount", igv)
                addProperty("total_amount", total); addProperty("discount_amount", 0); addProperty("payment_method", "Contado")
                addProperty("observations", observations ?: reference?.get("reasonDescription")?.asString ?: "Comprobante manual")
                reference?.let { ref -> addProperty("related_document_type", ref.get("type")?.asString); addProperty("related_document_serie", refSeries); if (refNumber != null) addProperty("related_document_correlative", refNumber); addProperty("related_document_full_number", refFullNumber); addProperty("sustento_tipo_nota", ref.get("reasonCode")?.asString); addProperty("sustento_descripcion", ref.get("reasonDescription")?.asString) }
                add("line_items", jsonArrayItems)
            }
            val rpcJsonPayload = JsonObject().apply { addProperty("p_store_id", storeId); add("p_data", documentData) }
            OutputStreamWriter(createConn.outputStream).use { it.write(rpcJsonPayload.toString()) }
            var responseCode = createConn.responseCode
            var responseBody = readResponse(createConn)
            if (responseCode !in 200..299 && responseBody.contains("42703")) {
                val missing = Regex("column [\\\"]?([A-Za-z0-9_.]+)[\\\"]? does not exist", RegexOption.IGNORE_CASE).find(responseBody)?.groupValues?.get(1)
                return failure("PostgreSQL 42703: columna inexistente${missing?.let { " '$it'" } ?: ""}. Respuesta: $responseBody")
            }
            if (responseCode !in 200..299 && responseBody.contains("PGRST202")) {
                val fallbackConn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/rpc/create_electronic_document"), "POST")
                fallbackConn.setRequestProperty("Content-Type", "application/json"); fallbackConn.doOutput = true
                val fallback = JsonObject().apply {
                    addProperty("p_store_id", storeId)
                    if (orderId.isNullOrBlank()) add("p_order_id", com.google.gson.JsonNull.INSTANCE) else addProperty("p_order_id", orderId)
                    addProperty("p_document_type", documentType); addProperty("p_customer_doc_type", customerDocType)
                    addProperty("p_customer_doc_number", clientRuc); addProperty("p_customer_name", clientName)
                    addProperty("p_customer_address", clientAddress); add("p_customer_email", com.google.gson.JsonNull.INSTANCE)
                    addProperty("p_currency", "PEN"); addProperty("p_subtotal", subtotal); addProperty("p_igv_amount", igv)
                    addProperty("p_total_amount", total); addProperty("p_discount_amount", 0); addProperty("p_payment_method", "Contado")
                    addProperty("p_observations", observations ?: reference?.get("reasonDescription")?.asString ?: "Comprobante manual")
                    add("p_user_id", com.google.gson.JsonNull.INSTANCE); addProperty("p_related_document_type", reference?.get("type")?.asString)
                    addProperty("p_related_document_serie", refSeries); if (refNumber != null) addProperty("p_related_document_correlative", refNumber)
                    addProperty("p_related_document_full_number", refFullNumber); addProperty("p_sustento_tipo_nota", reference?.get("reasonCode")?.asString)
                    addProperty("p_sustento_descripcion", reference?.get("reasonDescription")?.asString)
                }
                OutputStreamWriter(fallbackConn.outputStream).use { it.write(fallback.toString()) }
                responseCode = fallbackConn.responseCode
                responseBody = readResponse(fallbackConn)
            }
            if (responseCode !in 200..299) return failure("Error RPC $responseCode: $responseBody")
            val createdJson = JsonParser.parseString(responseBody)
            if (createdJson.isJsonObject && createdJson.asJsonObject.get("success")?.asBoolean == false) {
                val serverError = createdJson.asJsonObject.get("error")?.asString ?: createdJson.toString()
                return failure("RPC create_electronic_document rechazó $fullNumber: $serverError; payload incluía serie=$serie, correlativo=$correlative")
            }
            val documentId = when {
                createdJson.isJsonPrimitive -> createdJson.asString
                createdJson.isJsonObject && createdJson.asJsonObject.get("success")?.asBoolean == true -> createdJson.asJsonObject.get("document_id")?.asString ?: createdJson.asJsonObject.get("id")?.asString ?: ""
                else -> ""
            }
            if (documentId.isBlank()) return failure("RPC create_electronic_document respondió inválidamente para $fullNumber: $createdJson")
            val docFieldsConn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/electronic_documents?id=eq.$documentId&select=id,serie,correlative,full_number"))
            if (docFieldsConn.responseCode !in 200..299) return failure("Documento creado, pero no se pudo leer su número: ${readError(docFieldsConn)}")
            val docFields = JsonParser.parseString(BufferedReader(InputStreamReader(docFieldsConn.inputStream)).use { it.readText() }).asJsonArray
            if (docFields.size() == 0) return failure("No se encontró el comprobante recién creado")
            val createdDoc = docFields[0].asJsonObject
            val persistedFullNumber = createdDoc.get("full_number")?.asString ?: fullNumber
            val sendConn = authenticatedConnection(URL("${SupabaseConfig.URL}/functions/v1/send-to-sunat"), "POST")
            sendConn.setRequestProperty("Content-Type", "application/json"); sendConn.doOutput = true
            OutputStreamWriter(sendConn.outputStream).use { it.write(JsonObject().apply { addProperty("document_id", documentId); addProperty("store_id", storeId); reference?.let { r -> add("reference_document", r) } }.toString()) }
            if (sendConn.responseCode !in 200..299) {
                val error = readError(sendConn)
                updateDocumentStatus(documentId, "error", error)
                return failure("Comprobante guardado como $persistedFullNumber, pero falló el envío SUNAT: $error")
            }
            val sendJson = JsonParser.parseString(BufferedReader(InputStreamReader(sendConn.inputStream)).use { it.readText() })
            val sendOk = sendJson.isJsonObject && sendJson.asJsonObject.get("success")?.asBoolean == true
            val status = if (sendJson.isJsonObject) sendJson.asJsonObject.get("status")?.asString else null
            if (!sendOk) {
                val error = (if (sendJson.isJsonObject) sendJson.asJsonObject.get("error")?.asString else null) ?: "SUNAT rechazó el envío del comprobante"
                updateDocumentStatus(documentId, "rejected", error)
                val debug = sendJson.asJsonObject.get("stage")?.asString?.let { " (etapa: $it)" } ?: ""
                updateDocumentStatus(documentId, "error", error)
                return failure("Envío SUNAT $persistedFullNumber: $error$debug\nDiagnóstico: $sendJson")
            }
            if (status == "accepted" || status == "accepted_with_observations") updateDocumentStatus(documentId, status, null)
            return OperationResult(true, "Comprobante $persistedFullNumber enviado a SUNAT${if (status == "accepted" || status == "accepted_with_observations") " y aceptado" else " (pendiente de respuesta)"}", persistedFullNumber)
        } catch (e: Exception) {
            return failure("Error al emitir: ${e.localizedMessage}", e)
        }
    }

    private fun readError(conn: HttpURLConnection): String = try {
        val stream = conn.errorStream ?: conn.inputStream
        BufferedReader(InputStreamReader(stream)).use { it.readText() }.take(500)
    } catch (_: Exception) { "HTTP ${conn.responseCode}" }

    private fun readResponse(conn: HttpURLConnection): String = try {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        BufferedReader(InputStreamReader(stream)).use { it.readText() }.take(2000)
    } catch (e: Exception) { "HTTP ${conn.responseCode}: ${e.localizedMessage}" }

    private fun updateDocumentStatus(documentId: String, status: String, message: String?) {
        try {
            val conn = authenticatedConnection(URL("${SupabaseConfig.URL}/rest/v1/electronic_documents?id=eq.$documentId"), "PATCH")
            conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(JsonObject().apply { addProperty("status", status); if (message != null) addProperty("error_message", message.take(200)) }.toString()) }
            conn.responseCode
        } catch (_: Exception) { }
    }
}

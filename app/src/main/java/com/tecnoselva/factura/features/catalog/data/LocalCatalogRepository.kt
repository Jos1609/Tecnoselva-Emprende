package com.tecnoselva.factura.features.catalog.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tecnoselva.factura.features.catalog.model.LocalCatalogProduct
import java.io.File
import java.util.UUID

/** Catálogo guardado en el dispositivo y aislado por la cuenta que inició sesión. */
class LocalCatalogRepository(private val context: Context, accountScope: String) {
    private val scopeKey = accountScope.ifBlank { "local" }
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(80)
    private val preferences = context.getSharedPreferences("${PREFERENCES_NAME}_$scopeKey", Context.MODE_PRIVATE)
    private val imageDirectory = File(context.filesDir, "$IMAGE_DIRECTORY/$scopeKey")
    private val gson = Gson()

    fun getAll(): List<LocalCatalogProduct> {
        val json = preferences.getString(KEY_PRODUCTS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<LocalCatalogProduct>>() {}.type
            gson.fromJson<List<LocalCatalogProduct>>(json, type)
        }.getOrDefault(emptyList())
            .map { product ->
                product.copy(
                    variants = product.variants.orEmpty().map { variant ->
                        variant.copy(
                            imagePath = variant.imagePath.orEmpty(),
                            wholesalePrices = variant.wholesalePrices.orEmpty()
                        )
                    }
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun save(product: LocalCatalogProduct) {
        val products = getAll().toMutableList()
        val index = products.indexOfFirst { it.id == product.id }
        if (index >= 0) products[index] = product else products.add(product)
        persist(products)
        saveCategory(product.category)
        product.sku.toIntOrNull()?.let { usedNumber ->
            val nextNumber = preferences.getInt(KEY_NEXT_SKU, 1)
            if (usedNumber >= nextNumber) preferences.edit().putInt(KEY_NEXT_SKU, usedNumber + 1).apply()
        }
    }

    fun nextProductSku(): String {
        val largestExisting = getAll().mapNotNull { it.sku.toIntOrNull() }.maxOrNull() ?: 0
        val nextNumber = maxOf(1, preferences.getInt(KEY_NEXT_SKU, 1), largestExisting + 1)
        return nextNumber.toString().padStart(SKU_MINIMUM_LENGTH, '0')
    }

    fun getCategories(): List<String> {
        val saved = preferences.getString(KEY_CATEGORIES, null)?.let { json ->
            runCatching {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type)
            }.getOrNull()
        }.orEmpty()
        return (saved + getAll().map { it.category })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    fun delete(product: LocalCatalogProduct) {
        persist(getAll().filterNot { it.id == product.id })
        deleteOwnedImage(product.imagePath)
        product.variants.forEach { variant -> deleteOwnedImage(variant.imagePath) }
    }

    fun importImage(uri: Uri, previousPath: String): String {
        imageDirectory.mkdirs()
        val destination = File(imageDirectory, "${UUID.randomUUID()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo leer la imagen seleccionada")
        deleteOwnedImage(previousPath)
        return destination.absolutePath
    }

    fun deleteOwnedImage(path: String) {
        if (path.isBlank()) return
        val ownedDirectory = imageDirectory.canonicalFile
        val image = File(path).canonicalFile
        if (image.parentFile == ownedDirectory && image.exists()) image.delete()
    }

    private fun persist(products: List<LocalCatalogProduct>) {
        preferences.edit().putString(KEY_PRODUCTS, gson.toJson(products)).apply()
    }

    private fun saveCategory(category: String) {
        val normalized = category.trim()
        if (normalized.isBlank()) return
        val categories = getCategories().toMutableList()
        if (categories.none { it.equals(normalized, ignoreCase = true) }) categories.add(normalized)
        preferences.edit()
            .putString(KEY_CATEGORIES, gson.toJson(categories.sortedBy { it.lowercase() }))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "local_product_catalog"
        const val KEY_PRODUCTS = "products"
        const val KEY_CATEGORIES = "categories"
        const val KEY_NEXT_SKU = "next_sku"
        const val IMAGE_DIRECTORY = "catalog_images"
        const val SKU_MINIMUM_LENGTH = 4
    }
}

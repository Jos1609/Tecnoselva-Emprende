package com.tecnoselva.factura.features.pos.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tecnoselva.factura.features.pos.model.PosCatalogItem

/** Store-scoped catalog cache. It avoids a Supabase read on every POS navigation. */
class PosCatalogCache(context: Context) {
    private val preferences = context.getSharedPreferences("pos_catalog_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(storeId: String): CachedCatalog? {
        if (storeId.isBlank()) return null
        val json = preferences.getString(itemsKey(storeId), null) ?: return null
        val items = runCatching {
            val type = object : TypeToken<List<PosCatalogItem>>() {}.type
            gson.fromJson<List<PosCatalogItem>>(json, type)
        }.getOrNull() ?: return null
        return CachedCatalog(items, preferences.getLong(timestampKey(storeId), 0L))
    }

    fun save(storeId: String, items: List<PosCatalogItem>) {
        if (storeId.isBlank()) return
        preferences.edit()
            .putString(itemsKey(storeId), gson.toJson(items))
            .putLong(timestampKey(storeId), System.currentTimeMillis())
            .apply()
    }

    fun invalidate(storeId: String) {
        if (storeId.isBlank()) return
        preferences.edit().remove(timestampKey(storeId)).apply()
    }

    data class CachedCatalog(val items: List<PosCatalogItem>, val savedAt: Long) {
        val isFresh: Boolean
            get() = savedAt > 0L && System.currentTimeMillis() - savedAt < CACHE_TTL_MS
    }

    private fun itemsKey(storeId: String) = "items_$storeId"
    private fun timestampKey(storeId: String) = "saved_at_$storeId"

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}

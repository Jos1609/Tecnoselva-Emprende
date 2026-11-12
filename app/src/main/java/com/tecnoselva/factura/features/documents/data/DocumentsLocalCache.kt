package com.tecnoselva.factura.features.documents.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tecnoselva.factura.features.documents.model.Invoice

/** Keeps the last loaded document list on-device so opening previews does not require a request. */
class DocumentsLocalCache(context: Context) {
    private val preferences = context.getSharedPreferences("documents_local_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Invoice>>() {}.type

    fun load(): List<Invoice> = runCatching {
        gson.fromJson<List<Invoice>>(preferences.getString(KEY_DOCUMENTS, null), listType).orEmpty()
    }.getOrDefault(emptyList())

    fun save(documents: List<Invoice>) {
        preferences.edit().putString(KEY_DOCUMENTS, gson.toJson(documents, listType)).apply()
    }

    private companion object {
        const val KEY_DOCUMENTS = "documents"
    }
}

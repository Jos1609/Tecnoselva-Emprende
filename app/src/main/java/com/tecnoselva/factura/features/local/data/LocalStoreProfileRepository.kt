package com.tecnoselva.factura.features.local.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.tecnoselva.factura.features.local.model.LocalStoreProfile
import java.io.File
import java.util.UUID

class LocalStoreProfileRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val logoDirectory = File(context.filesDir, LOGO_DIRECTORY)

    val localStoreId: String
        get() = preferences.getString(KEY_LOCAL_STORE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_LOCAL_STORE_ID, it).apply()
        }

    fun getProfile(): LocalStoreProfile? = preferences.getString(KEY_PROFILE, null)?.let { json ->
        runCatching { gson.fromJson(json, LocalStoreProfile::class.java) }.getOrNull()
    }

    fun save(profile: LocalStoreProfile) {
        preferences.edit()
            .putString(KEY_LOCAL_STORE_ID, profile.localStoreId)
            .putString(KEY_PROFILE, gson.toJson(profile))
            .apply()
    }

    fun importLogo(uri: Uri, previousPath: String): String {
        logoDirectory.mkdirs()
        val destination = File(logoDirectory, "${UUID.randomUUID()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo leer el logo seleccionado")
        deleteLogo(previousPath)
        return destination.absolutePath
    }

    fun deleteLogo(path: String) {
        if (path.isBlank()) return
        val directory = logoDirectory.canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile == directory && file.exists()) file.delete()
    }

    private companion object {
        const val PREFERENCES_NAME = "tecnoselva_local_store"
        const val KEY_LOCAL_STORE_ID = "local_store_id"
        const val KEY_PROFILE = "profile"
        const val LOGO_DIRECTORY = "local_store_logos"
    }
}

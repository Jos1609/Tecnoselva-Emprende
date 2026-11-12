package com.tecnoselva.factura.features.auth.data

import android.util.Base64
import com.google.gson.JsonParser
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.core.session.SessionManager

class SessionExpiredException(
    message: String = "Tu sesión venció. Inicia sesión nuevamente para continuar."
) : IllegalStateException(message)

class SessionRefreshUnavailableException(
    message: String = "No se pudo renovar la sesión. Revisa tu conexión e inténtalo nuevamente."
) : IllegalStateException(message)

/** Owns persisted session state and guarantees a usable token for feature repositories. */
class SessionRepository(private val session: SessionManager) {
    val isLoggedIn: Boolean get() = session.isLoggedIn
    val userId: String get() = session.currentUserId
    val storeId: String get() = session.storeId

    init {
        SupabaseService.setAccessToken(session.accessToken)
    }

    /**
     * Executes one authenticated operation. Tokens are renewed only near expiration, and an
     * authorization failure gets exactly one forced refresh plus one retry.
     */
    fun <T> withValidSession(operation: () -> T): T {
        ensureValidAccessToken()
        return try {
            operation()
        } catch (error: Exception) {
            if (!error.isAuthorizationFailure()) throw error
            refreshAccessToken(force = true)
            try {
                operation()
            } catch (retryError: Exception) {
                if (retryError.isAuthorizationFailure()) throw SessionExpiredException()
                throw retryError
            }
        }
    }

    /** Kept for callers that only need to warm up the shared authenticated client. */
    fun refreshAccessToken(): Boolean {
        ensureValidAccessToken()
        return true
    }

    private fun ensureValidAccessToken() {
        val current = session.accessToken
        if (current.isNotBlank() && !current.expiresWithin(TOKEN_REFRESH_LEEWAY_SECONDS)) {
            SupabaseService.setAccessToken(current)
            return
        }
        refreshAccessToken(force = false)
    }

    private fun refreshAccessToken(force: Boolean) {
        synchronized(refreshLock) {
            // Another request may already have rotated both tokens while this one waited.
            val latestAccess = session.accessToken
            if (!force && latestAccess.isNotBlank() && !latestAccess.expiresWithin(TOKEN_REFRESH_LEEWAY_SECONDS)) {
                SupabaseService.setAccessToken(latestAccess)
                return
            }

            when (val result = SupabaseService.refreshSession(session.refreshToken)) {
                is SupabaseService.SessionRefreshResult.Success -> {
                    // Commit the rotated pair together so a later request never sees half a session.
                    session.updateTokens(result.accessToken, result.refreshToken)
                    SupabaseService.setAccessToken(result.accessToken)
                }
                is SupabaseService.SessionRefreshResult.Rejected -> {
                    throw SessionExpiredException()
                }
                is SupabaseService.SessionRefreshResult.Unavailable -> {
                    // A still-valid token remains usable when the refresh service is temporarily down.
                    if (latestAccess.isNotBlank() && !latestAccess.isExpired()) {
                        SupabaseService.setAccessToken(latestAccess)
                    } else {
                        throw SessionRefreshUnavailableException(
                            "No se pudo renovar la sesión: ${result.message}. Revisa tu conexión e inténtalo nuevamente."
                        )
                    }
                }
            }
        }
    }

    private fun String.isExpired(): Boolean = expiresWithin(0)

    private fun String.expiresWithin(seconds: Long): Boolean {
        val expiration = runCatching {
            val payload = split('.').getOrNull(1) ?: return@runCatching null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JsonParser.parseString(String(decoded, Charsets.UTF_8)).asJsonObject.get("exp")?.asLong
        }.getOrNull() ?: return true
        return expiration <= (System.currentTimeMillis() / 1000L) + seconds
    }

    private fun Throwable.isAuthorizationFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val text = current.message.orEmpty().lowercase()
            if (text.contains("http 401") || text.contains("http 403") ||
                text.contains("jwt expired") || text.contains("invalid jwt") ||
                text.contains("token is expired")) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val TOKEN_REFRESH_LEEWAY_SECONDS = 5 * 60L
        val refreshLock = Any()
    }
}

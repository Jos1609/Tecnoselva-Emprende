package com.tecnoselva.factura.features.auth.data

import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.auth.model.LoginResult

/** Authentication feature entry point. */
class AuthService {
    fun signIn(email: String, password: String): LoginResult = SupabaseService.login(email, password)

    fun setAccessToken(token: String) = SupabaseService.setAccessToken(token)
}

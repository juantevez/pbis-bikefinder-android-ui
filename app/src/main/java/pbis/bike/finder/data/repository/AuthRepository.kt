package pbis.bike.finder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Login, registro y salida de sesión.
 *
 * El perfil se guarda en memoria y no en disco. En web se cachea en
 * `sessionStorage` justamente para que muera con la pestaña: con `localStorage`
 * habría que acordarse de borrarlo en los cuatro lugares que cierran sesión, y
 * olvidarse de uno significa mostrarle el nombre del usuario anterior al
 * siguiente que entre. Acá el equivalente es memoria del proceso — si la app se
 * reinicia, se vuelve a pedir con `/auth/me`. Es un cache, no la fuente de verdad.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStorage,
    private val sessionManager: SessionManager,
    private val json: Json,
) {
    @Volatile
    private var cachedProfile: UserInfoDto? = null

    val hasSession: Flow<Boolean> = tokenStore.hasSession

    /**
     * Login. Guarda los tokens y cachea el perfil que viene con ellos.
     *
     * `/auth/login` devuelve el usuario completo junto con los tokens, así que
     * cachearlo evita un `/auth/me` en el arranque de la sesión.
     */
    suspend fun login(email: String, password: String): ApiResult<UserInfoDto> {
        val result = apiCall(json) { api.login(LoginRequestDto(email.trim(), password)) }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                tokenStore.save(body.accessToken, body.refreshToken)
                cachedProfile = body.user
                body.user
                    ?.let { ApiResult.Success(it) }
                    // 200 con tokens pero sin usuario: la sesión sirve igual, así
                    // que se pide el perfil aparte en vez de fallar el login.
                    ?: profile()
            }

            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }
    }

    suspend fun register(
        email: String,
        password: String,
        fullName: String,
    ): ApiResult<UserInfoDto> {
        val result = apiCall(json) {
            api.register(RegisterRequestDto(email.trim(), password, fullName.trim()))
        }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                tokenStore.save(body.accessToken, body.refreshToken)
                cachedProfile = body.user
                body.user?.let { ApiResult.Success(it) } ?: profile()
            }

            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }
    }

    /** Perfil, del cache si está. */
    suspend fun profile(forceRefresh: Boolean = false): ApiResult<UserInfoDto> {
        if (!forceRefresh) cachedProfile?.let { return ApiResult.Success(it) }

        return apiCall(json) { api.me() }.also {
            if (it is ApiResult.Success) cachedProfile = it.data
        }
    }

    /**
     * Actualiza el perfil.
     *
     * `null` significa "no tocar" del lado del backend, así que quien construya
     * el request tiene que distinguir "el usuario borró el campo" de "el usuario
     * no lo tocó". No se puede vaciar un campo desde acá; el front web tampoco
     * puede, y es una limitación del contrato, no de este método.
     *
     * Refresca el cache con lo que devolvió el servidor y no con lo que se
     * mandó: el backend normaliza —recorta espacios, valida el teléfono— y el
     * dashboard lee este mismo cache para el nombre del encabezado. Mostrar ahí
     * lo que se tipeó en vez de lo que se guardó es cómo se termina con dos
     * pantallas diciendo cosas distintas del mismo dato.
     */
    suspend fun updateProfile(request: UpdateProfileRequestDto): ApiResult<UserInfoDto> =
        apiCall(json) { api.updateProfile(request) }.also {
            if (it is ApiResult.Success) cachedProfile = it.data
        }

    /**
     * Cierra sesión.
     *
     * El POST a `/auth/logout` es best-effort: si falla, igual se borran los
     * tokens locales. Dejar al usuario adentro porque el servidor no contestó
     * sería lo peor de los dos mundos — cree que salió y no salió.
     */
    suspend fun logout() {
        val refreshToken = tokenStore.refreshToken()
        if (refreshToken != null) {
            apiCall(json) { api.logout(LogoutRequestDto(refreshToken)) }
        }
        cachedProfile = null
        sessionManager.closeSession()
    }
}

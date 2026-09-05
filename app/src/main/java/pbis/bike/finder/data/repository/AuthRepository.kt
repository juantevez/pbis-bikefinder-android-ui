package pbis.bike.finder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.orThrow
import pbis.bike.finder.data.remote.map
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.MfaLoginRequestDto
import pbis.bike.finder.data.remote.dto.RecoveryCodesDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.TotpCodeRequestDto
import pbis.bike.finder.data.remote.dto.TotpSetupDto
import pbis.bike.finder.data.remote.dto.TotpStatusDto
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
/**
 * Cómo terminó un `login()`.
 *
 * Existe porque la contraseña correcta ya no implica sesión: si la cuenta tiene
 * segundo factor, el backend devuelve un challenge y falta una etapa. Devolver
 * `ApiResult<UserInfoDto>` obligaría a representar ese estado como un error, que
 * es exactamente lo que no es.
 */
sealed interface LoginOutcome {

    /** Sesión abierta: los tokens ya están guardados. */
    data class Completed(val profile: UserInfoDto) : LoginOutcome

    /** Falta el código. El challenge vale cinco minutos y NO es una sesión. */
    data class MfaRequired(val mfaToken: String) : LoginOutcome
}

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
    suspend fun login(email: String, password: String): ApiResult<LoginOutcome> {
        val result = apiCall(json) { api.login(LoginRequestDto(email.trim(), password)) }

        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                // Con segundo factor la respuesta es 200 pero sin tokens: el
                // challenge se devuelve a quien llamó y NO se guarda nada. Meterlo
                // en el TokenStorage dejaría media sesión en disco.
                if (body.mfaRequired) {
                    body.mfaToken
                        ?.let { ApiResult.Success(LoginOutcome.MfaRequired(it)) }
                        ?: ApiResult.Malformed(
                            IllegalStateException("mfaRequired sin mfaToken"),
                        )
                } else {
                    abrirSesion(body).map { LoginOutcome.Completed(it) }
                }
            }

            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }
    }

    /**
     * Segunda etapa: canjea el challenge más el código por la sesión.
     *
     * El `code` puede ser el de la app o uno de recuperación; los distingue el
     * backend, no la app.
     */
    suspend fun verifyMfa(mfaToken: String, code: String): ApiResult<UserInfoDto> {
        val result = apiCall(json) {
            api.loginWith2fa(MfaLoginRequestDto(mfaToken, code.trim()))
        }

        return when (result) {
            is ApiResult.Success -> abrirSesion(result.data)
            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }
    }

    /**
     * Guarda los tokens de una respuesta que debería traerlos y deja el perfil
     * en cache.
     *
     * La guarda de los nulls no es paranoia de tipos: desde que existe el
     * segundo factor hay respuestas 200 legítimas sin tokens, y confundir una de
     * esas con una sesión dejaría al usuario "adentro" con un
     * `Authorization: Bearer null`.
     */
    private suspend fun abrirSesion(body: AuthResponseDto): ApiResult<UserInfoDto> {
        val accessToken = body.accessToken
        val refreshToken = body.refreshToken
        if (accessToken == null || refreshToken == null) {
            return ApiResult.Malformed(
                IllegalStateException("Respuesta de sesión sin tokens"),
            )
        }

        tokenStore.save(accessToken, refreshToken)
        cachedProfile = body.user
        return body.user
            ?.let { ApiResult.Success(it) }
            // 200 con tokens pero sin usuario: la sesión sirve igual, así que se
            // pide el perfil aparte en vez de fallar el login.
            ?: profile()
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
            // Una cuenta recién creada nunca tiene segundo factor, así que acá
            // siempre vienen los tokens; se pasa por abrirSesion igual para no
            // duplicar la guarda.
            is ApiResult.Success -> abrirSesion(result.data)

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
     * `null` significa "no tocar" para el nombre, el teléfono, el género y la
     * fecha —van bajo un `if (x != null)` del lado del backend—, así que esos
     * campos no se pueden vaciar desde acá. **Los cuatro de la ubicación son la
     * excepción**: se asignan siempre, y ahí un null borra. Ver
     * [UpdateProfileRequestDto].
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

    // ── Segundo factor ───────────────────────────────────────────────────────

    /**
     * Estado del segundo factor.
     *
     * **No se infiere ni se cachea.** `GET /auth/me` no habla de seguridad, y
     * después de cada operación se vuelve a pedir en vez de asumir en qué quedó
     * la pantalla: el factor se puede haber activado o dado de baja desde otro
     * dispositivo.
     */
    suspend fun totpStatus(): ApiResult<TotpStatusDto> = apiCall(json) { api.totpStatus() }

    /**
     * Empieza el enrolamiento: genera el secreto y la URI `otpauth://`.
     *
     * **No activa nada.** Hasta [confirmTotp] el factor no rige, así que
     * abandonar acá no deja la cuenta a medias: el próximo setup reemplaza el
     * secreto sin confirmar.
     */
    suspend fun setupTotp(): ApiResult<TotpSetupDto> = apiCall(json) { api.totpSetup() }

    /** Activa el factor. Devuelve los códigos de recuperación **una sola vez**. */
    suspend fun confirmTotp(code: String): ApiResult<RecoveryCodesDto> =
        apiCall(json) { api.totpConfirm(TotpCodeRequestDto(code)) }

    /** Lote nuevo de códigos; el anterior deja de servir. */
    suspend fun regenerateRecoveryCodes(code: String): ApiResult<RecoveryCodesDto> =
        apiCall(json) { api.totpRecoveryCodes(TotpCodeRequestDto(code)) }

    /** Da de baja el factor. Acepta un código de la app **o** uno de recuperación. */
    suspend fun disableTotp(code: String): ApiResult<Unit> =
        apiCall(json) { api.totpDisable(TotpCodeRequestDto(code)).orThrow() }

    /**
     * Pide el mail con el link para elegir una contraseña nueva.
     *
     * El link que llega apunta al front web (`EmailAdapter` lo arma como
     * `frontendUrl + "/reset-password.html?token=…"`), así que la contraseña se
     * elige en el navegador: la app sólo dispara el envío. Terminar el flujo acá
     * adentro necesitaría que el mail apuntara a un deep link, y eso se cambia
     * en auth-service, no en la app.
     */
    suspend fun requestPasswordReset(email: String): ApiResult<Unit> =
        apiCall(json) { api.requestPasswordReset(RequestPasswordResetDto(email)).orThrow() }

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

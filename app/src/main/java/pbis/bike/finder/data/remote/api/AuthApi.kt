package pbis.bike.finder.data.remote.api

import pbis.bike.finder.data.remote.HEADER_SKIP_AUTH
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.MfaLoginRequestDto
import pbis.bike.finder.data.remote.dto.RecoveryCodesDto
import pbis.bike.finder.data.remote.dto.TotpCodeRequestDto
import pbis.bike.finder.data.remote.dto.TotpSetupDto
import pbis.bike.finder.data.remote.dto.TotpStatusDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * auth-service, vía gateway.
 *
 * Las que devuelven [Response] son las que necesitan mirar el status crudo:
 * `refresh` distingue 4xx de 5xx para decidir si la sesión murió o si sólo se
 * cayó la red, y esa diferencia se pierde si Retrofit lanza excepción.
 */
interface AuthApi {

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    /**
     * Segunda etapa del login. Sin Bearer: el usuario todavía no tiene sesión —
     * lo que presenta es el challenge de la primera etapa, en el body.
     */
    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/login/2fa")
    suspend fun loginWith2fa(@Body body: MfaLoginRequestDto): AuthResponseDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    /**
     * Sin Bearer y **sin** el [pbis.bike.finder.data.remote.TokenAuthenticator]:
     * si el refresh diera 401, reintentar refrescando sería recursión infinita.
     */
    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequestDto): Response<AuthResponseDto>

    // ── Segundo factor ───────────────────────────────────────────────────────

    @GET("auth/2fa/status")
    suspend fun totpStatus(): TotpStatusDto

    /** Genera el secreto. **No** activa nada: eso lo hace [totpConfirm]. */
    @POST("auth/2fa/setup")
    suspend fun totpSetup(): TotpSetupDto

    /** Activa el factor y devuelve los códigos de recuperación, una sola vez. */
    @POST("auth/2fa/confirm")
    suspend fun totpConfirm(@Body body: TotpCodeRequestDto): RecoveryCodesDto

    /** Emite un lote nuevo e invalida el anterior. Exige un código válido. */
    @POST("auth/2fa/recovery-codes")
    suspend fun totpRecoveryCodes(@Body body: TotpCodeRequestDto): RecoveryCodesDto

    /**
     * Borra el secreto y los códigos. Responde 204 sin cuerpo.
     *
     * Es POST y no DELETE aunque borre: lleva body —el código— y varios proxies
     * tratan el body de un DELETE como opcional o lo descartan.
     */
    @POST("auth/2fa/disable")
    suspend fun totpDisable(@Body body: TotpCodeRequestDto): Response<Unit>

    /** Responde 204 sin cuerpo. */
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto): Response<Unit>

    @GET("auth/me")
    suspend fun me(): UserInfoDto

    @PUT("auth/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequestDto): UserInfoDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/verify-email")
    suspend fun verifyEmail(@Body body: VerifyEmailDto): Response<Unit>

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/resend-verification")
    suspend fun resendVerification(@Body body: ResendVerificationDto): Response<Unit>

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/reset-password/request")
    suspend fun requestPasswordReset(@Body body: RequestPasswordResetDto): Response<Unit>

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("auth/reset-password/confirm")
    suspend fun confirmPasswordReset(@Body body: ConfirmPasswordResetDto): Response<Unit>
}

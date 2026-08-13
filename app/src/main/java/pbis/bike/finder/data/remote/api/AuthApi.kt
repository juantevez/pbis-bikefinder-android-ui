package pbis.bike.finder.data.remote.api

import pbis.bike.finder.data.remote.HEADER_SKIP_AUTH
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
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

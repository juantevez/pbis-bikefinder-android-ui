package pbis.bike.finder.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.MfaLoginRequestDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RecoveryCodesDto
import pbis.bike.finder.data.remote.dto.TotpCodeRequestDto
import pbis.bike.finder.data.remote.dto.TotpSetupDto
import pbis.bike.finder.data.remote.dto.TotpStatusDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import retrofit2.Response
import java.io.IOException
import javax.inject.Provider

/**
 * La regla que estos tests protegen: **una sesión sólo se pierde cuando el
 * servidor dijo que el token no vale.**
 *
 * En el front web esto estuvo mal en siete archivos a la vez: el catch del
 * refresh no distinguía "el token no sirve" de "no hubo respuesta", así que
 * cualquier bache de red mandaba al login. En un teléfono, donde la conexión se
 * corta sola varias veces por día, ese bug haría la app inusable.
 */
class SessionManagerTest {

    private class FakeTokenStore(
        var access: String? = "access-viejo",
        var refresh: String? = "refresh-viejo",
    ) : TokenStorage {
        var cleared = false

        override val hasSession: Flow<Boolean> get() = flowOf(refresh != null)
        override suspend fun accessToken() = access
        override suspend fun refreshToken() = refresh
        override suspend fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override suspend fun clear() {
            cleared = true
            access = null
            refresh = null
        }
    }

    /** Sólo implementa `refresh`; el resto no participa de esta lógica. */
    private class FakeAuthApi(
        private val onRefresh: () -> Response<AuthResponseDto>,
    ) : AuthApi {
        override suspend fun refresh(body: RefreshTokenRequestDto) = onRefresh()

        override suspend fun login(body: LoginRequestDto) = notUsed()
        override suspend fun loginWith2fa(body: MfaLoginRequestDto) = notUsed()
        override suspend fun register(body: RegisterRequestDto) = notUsed()
        override suspend fun logout(body: LogoutRequestDto) = notUsed()
        override suspend fun totpStatus(): TotpStatusDto = notUsed()
        override suspend fun totpSetup(): TotpSetupDto = notUsed()
        override suspend fun totpConfirm(body: TotpCodeRequestDto): RecoveryCodesDto = notUsed()
        override suspend fun totpRecoveryCodes(body: TotpCodeRequestDto): RecoveryCodesDto =
            notUsed()

        override suspend fun totpDisable(body: TotpCodeRequestDto): Response<Unit> = notUsed()
        override suspend fun me(): UserInfoDto = notUsed()
        override suspend fun updateProfile(body: UpdateProfileRequestDto): UserInfoDto = notUsed()
        override suspend fun verifyEmail(body: VerifyEmailDto) = notUsed()
        override suspend fun resendVerification(body: ResendVerificationDto) = notUsed()
        override suspend fun requestPasswordReset(body: RequestPasswordResetDto) = notUsed()
        override suspend fun confirmPasswordReset(body: ConfirmPasswordResetDto) = notUsed()

        private fun notUsed(): Nothing = throw UnsupportedOperationException()
    }

    private fun errorResponse(code: Int): Response<AuthResponseDto> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    private fun manager(store: TokenStorage, api: AuthApi) =
        SessionManager(store, Provider { api })

    @Test
    fun `un 401 del refresh vence la sesion`() = runTest {
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { errorResponse(401) })

        assertEquals(RefreshOutcome.Expired, sut.refresh())
    }

    @Test
    fun `un 503 del gateway NO toca la sesion`() = runTest {
        // El gateway devuelve 503 cuando se le vence la espera o el circuito está
        // abierto. Eso no dice nada sobre el token: dice que el backend no pudo
        // contestar. Desloguear acá es perder la sesión por una caída del servidor.
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { errorResponse(503) })

        assertEquals(RefreshOutcome.NoNetwork, sut.refresh())
        assertNotNull(store.refresh)
    }

    @Test
    fun `un 429 tampoco vence la sesion`() = runTest {
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { errorResponse(429) })

        assertEquals(RefreshOutcome.NoNetwork, sut.refresh())
        assertNotNull(store.refresh)
    }

    @Test
    fun `sin respuesta del servidor la sesion queda intacta`() = runTest {
        // Wifi caído, backend apagado, portal cautivo: no sabemos nada del token.
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { throw IOException("sin red") })

        assertEquals(RefreshOutcome.NoNetwork, sut.refresh())
        assertNotNull(store.refresh)
    }

    @Test
    fun `un refresh exitoso rota los dos tokens`() = runTest {
        val store = FakeTokenStore()
        val sut = manager(
            store,
            FakeAuthApi {
                Response.success(
                    AuthResponseDto(accessToken = "access-nuevo", refreshToken = "refresh-nuevo")
                )
            },
        )

        assertEquals(RefreshOutcome.Ok, sut.refresh())
        assertEquals("access-nuevo", store.access)
        // El refresh token también rota: guardar sólo el access deja al siguiente
        // refresh usando uno que el backend ya invalidó.
        assertEquals("refresh-nuevo", store.refresh)
    }

    @Test
    fun `sin refresh token guardado la sesion ya esta vencida`() = runTest {
        val store = FakeTokenStore(refresh = null)
        val sut = manager(store, FakeAuthApi { errorResponse(500) })

        assertEquals(RefreshOutcome.Expired, sut.refresh())
    }

    @Test
    fun `un 200 con cuerpo ilegible no deja una sesion a medias`() = runTest {
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { Response.success(null) })

        assertEquals(RefreshOutcome.Expired, sut.refresh())
    }

    @Test
    fun `cerrar sesion borra los tokens y avisa`() = runTest {
        val store = FakeTokenStore()
        val sut = manager(store, FakeAuthApi { errorResponse(401) })

        sut.closeSession()

        assertNull(store.access)
        assertEquals(true, store.cleared)
    }
}

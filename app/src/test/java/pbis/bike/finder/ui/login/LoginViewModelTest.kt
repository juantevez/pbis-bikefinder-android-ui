package pbis.bike.finder.ui.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.SessionManager
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
import pbis.bike.finder.data.repository.AuthRepository
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Provider

/**
 * El login en dos pasos.
 *
 * Lo que se verifica no es "que ande": es que una respuesta 200 **sin tokens**
 * —la que llega cuando la cuenta tiene segundo factor— no se confunda con una
 * sesión abierta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeAuthApi(
        var loginResponse: () -> AuthResponseDto = { sesion() },
        var mfaResponse: () -> AuthResponseDto = { sesion() },
    ) : AuthApi {
        var ultimoCanje: MfaLoginRequestDto? = null

        override suspend fun login(body: LoginRequestDto): AuthResponseDto = loginResponse()

        override suspend fun loginWith2fa(body: MfaLoginRequestDto): AuthResponseDto {
            ultimoCanje = body
            return mfaResponse()
        }

        override suspend fun register(body: RegisterRequestDto) = notUsed()
        override suspend fun refresh(body: RefreshTokenRequestDto): Response<AuthResponseDto> =
            notUsed()

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

        companion object {
            fun sesion() = AuthResponseDto(
                accessToken = "access",
                refreshToken = "refresh",
                user = UserInfoDto(id = "u-1", email = "juan@example.com"),
            )

            fun challenge() = AuthResponseDto(mfaRequired = true, mfaToken = "challenge-token")
        }
    }

    /** Registra lo que se guardó: es la mitad de lo que estos tests verifican. */
    private class FakeTokenStore : TokenStorage {
        var guardados: Pair<String, String>? = null

        override val hasSession: Flow<Boolean> get() = flowOf(false)
        override suspend fun accessToken() = guardados?.first
        override suspend fun refreshToken() = guardados?.second
        override suspend fun save(accessToken: String, refreshToken: String) {
            guardados = accessToken to refreshToken
        }

        override suspend fun clear() {
            guardados = null
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun viewModel(api: FakeAuthApi, store: FakeTokenStore): LoginViewModel =
        LoginViewModel(
            AuthRepository(
                api = api,
                tokenStore = store,
                sessionManager = SessionManager(store, Provider { api }),
                json = json,
            ),
        )

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())),
    )

    private fun LoginViewModel.credenciales() {
        onEmailChange("juan@example.com")
        onPasswordChange("secret1234")
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `sin segundo factor el login abre la sesion`() = runTest {
        val store = FakeTokenStore()
        val sut = viewModel(FakeAuthApi(), store)

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        assertTrue(sut.state.value.loggedIn)
        assertEquals("access" to "refresh", store.guardados)
    }

    @Test
    fun `con segundo factor NO guarda tokens y pasa al segundo paso`() = runTest {
        // El bug que esto previene: la respuesta es 200, así que el camino feliz
        // la tomaba por buena. Con los tokens en null, la sesión quedaba a medias.
        val store = FakeTokenStore()
        val sut = viewModel(FakeAuthApi(loginResponse = { FakeAuthApi.challenge() }), store)

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        assertTrue(state.awaitingMfa)
        assertFalse(state.loggedIn)
        assertNull(store.guardados)
        // submitting se libera: la pantalla no navega, cambia de paso.
        assertFalse(state.submitting)
    }

    @Test
    fun `el codigo canjea el challenge y abre la sesion`() = runTest {
        val store = FakeTokenStore()
        val api = FakeAuthApi(loginResponse = { FakeAuthApi.challenge() })
        val sut = viewModel(api, store)

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        sut.onMfaCodeChange("492039")
        sut.submitMfaCode()
        advanceUntilIdle()

        assertTrue(sut.state.value.loggedIn)
        assertEquals("access" to "refresh", store.guardados)
        // El challenge que viaja es el que devolvió la primera etapa.
        assertEquals("challenge-token", api.ultimoCanje?.mfaToken)
        assertEquals("492039", api.ultimoCanje?.code)
    }

    @Test
    fun `un codigo de recuperacion viaja por el mismo campo`() = runTest {
        // La app no distingue TOTP de código de recuperación: lo hace el backend.
        val api = FakeAuthApi(loginResponse = { FakeAuthApi.challenge() })
        val sut = viewModel(api, FakeTokenStore())

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        sut.onMfaCodeChange("a3km7-qp29x")
        sut.submitMfaCode()
        advanceUntilIdle()

        assertEquals("a3km7-qp29x", api.ultimoCanje?.code)
    }

    @Test
    fun `un codigo invalido deja al usuario en el segundo paso`() = runTest {
        val api = FakeAuthApi(
            loginResponse = { FakeAuthApi.challenge() },
            mfaResponse = { throw httpError(401, """{"code":"INVALID_CREDENTIALS","message":"Código inválido"}""") },
        )
        val store = FakeTokenStore()
        val sut = viewModel(api, store)

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        sut.onMfaCodeChange("000000")
        sut.submitMfaCode()
        advanceUntilIdle()

        val state = sut.state.value
        assertTrue(state.awaitingMfa)
        assertFalse(state.loggedIn)
        assertNull(store.guardados)
        assertFalse(state.submitting)
        assertTrue(state.formError != null)
    }

    @Test
    fun `un challenge vencido devuelve al paso de la contrasena`() = runTest {
        // INVALID_TOTP_CODE se reintenta; INVALID_TOKEN no: son los cinco minutos
        // del challenge agotados, y reintentar el código no lo revive.
        val api = FakeAuthApi(
            loginResponse = { FakeAuthApi.challenge() },
            mfaResponse = { throw httpError(401, """{"code":"INVALID_TOKEN","message":"El proceso de login expiró"}""") },
        )
        val sut = viewModel(api, FakeTokenStore())

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        sut.onMfaCodeChange("492039")
        sut.submitMfaCode()
        advanceUntilIdle()

        val state = sut.state.value
        assertFalse(state.awaitingMfa)
        assertEquals("", state.mfaCode)
        assertTrue(state.formError!!.contains("expiró"))
    }

    @Test
    fun `volver descarta el challenge`() = runTest {
        val sut = viewModel(FakeAuthApi(loginResponse = { FakeAuthApi.challenge() }), FakeTokenStore())

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()
        sut.onMfaCodeChange("492039")

        sut.cancelMfa()

        val state = sut.state.value
        assertFalse(state.awaitingMfa)
        assertEquals("", state.mfaCode)
        // La contraseña se conserva: el usuario no se equivocó en ella.
        assertEquals("secret1234", state.password)
    }

    @Test
    fun `un mfaRequired sin token no se toma por sesion`() = runTest {
        // Respuesta incoherente del backend. Antes que dejar la pantalla en un
        // segundo paso sin challenge que enviar, se reporta como error.
        val store = FakeTokenStore()
        val sut = viewModel(
            FakeAuthApi(loginResponse = { AuthResponseDto(mfaRequired = true) }),
            store,
        )

        sut.credenciales()
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        assertFalse(state.awaitingMfa)
        assertFalse(state.loggedIn)
        assertNull(store.guardados)
        assertTrue(state.formError != null)
    }
}

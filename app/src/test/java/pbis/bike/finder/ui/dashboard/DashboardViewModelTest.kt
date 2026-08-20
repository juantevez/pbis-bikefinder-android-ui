package pbis.bike.finder.ui.dashboard

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.api.DashboardApi
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.ResumenUsuarioDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.DashboardRepository
import pbis.bike.finder.testing.StubBicycleApi
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Provider

/**
 * El dashboard no tiene lógica de negocio: es un hub. Lo que sí tiene —y lo que
 * cubren estos tests— es una regla de resiliencia que no se ve leyendo la UI:
 * **cuando el agregador falla, la pantalla sigue siendo navegable**. En el front
 * web ese fallo se llevaba puesta media pantalla, porque la misma respuesta
 * alimentaba los números y los selectores de bici.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeDashboardApi(
        var respond: () -> ResumenUsuarioDto = { ResumenUsuarioDto() },
    ) : DashboardApi {
        override suspend fun userSummary() = respond()
    }

    /** El perfil es decoración en esta pantalla; sólo importa que no explote. */
    private class FakeAuthApi(private val profile: () -> UserInfoDto) : AuthApi {
        override suspend fun me(): UserInfoDto = profile()

        override suspend fun login(body: LoginRequestDto) = notUsed()
        override suspend fun register(body: RegisterRequestDto) = notUsed()
        override suspend fun refresh(body: RefreshTokenRequestDto): Response<AuthResponseDto> =
            notUsed()

        override suspend fun logout(body: LogoutRequestDto) = notUsed()
        override suspend fun updateProfile(body: UpdateProfileRequestDto): UserInfoDto = notUsed()
        override suspend fun verifyEmail(body: VerifyEmailDto) = notUsed()
        override suspend fun resendVerification(body: ResendVerificationDto) = notUsed()
        override suspend fun requestPasswordReset(body: RequestPasswordResetDto) = notUsed()
        override suspend fun confirmPasswordReset(body: ConfirmPasswordResetDto) = notUsed()

        private fun notUsed(): Nothing = throw UnsupportedOperationException()
    }

    private class FakeTokenStore : TokenStorage {
        override val hasSession: Flow<Boolean> get() = flowOf(true)
        override suspend fun accessToken() = "access"
        override suspend fun refreshToken() = "refresh"
        override suspend fun save(accessToken: String, refreshToken: String) = Unit
        override suspend fun clear() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Sólo implementa el DELETE: es lo único que el dashboard le pide a bicis. */
    private class FakeBicycleApi(
        var respond: () -> Response<Unit> = { Response.success(Unit) },
    ) : StubBicycleApi() {
        val deleted = mutableListOf<String>()

        override suspend fun delete(id: String): Response<Unit> {
            deleted += id
            return respond()
        }
    }

    private fun viewModel(
        dashboardApi: DashboardApi,
        bicycleApi: BicycleApi = FakeBicycleApi(),
        profile: () -> UserInfoDto = { UserInfoDto(id = "u-1", email = "juan@example.com") },
    ): DashboardViewModel {
        val authApi = FakeAuthApi(profile)
        val store = FakeTokenStore()
        return DashboardViewModel(
            dashboardRepository = DashboardRepository(dashboardApi, json),
            bicycleRepository = BicycleRepository(bicycleApi, json),
            authRepository = AuthRepository(
                api = authApi,
                tokenStore = store,
                sessionManager = SessionManager(store, Provider { authApi }),
                json = json,
            ),
        )
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Unit>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `el resumen llena los tres numeros y la lista de bicicletas`() = runTest {
        val sut = viewModel(
            FakeDashboardApi {
                ResumenUsuarioDto(
                    totalBicicletas = 4,
                    totalComponentes = 12,
                    totalReportesActivos = 1,
                    bicicletas = listOf(BicicletaResumenDto(id = "bici-1", marca = "Trek")),
                )
            },
        )

        sut.loadSummary()
        advanceUntilIdle()

        val state = sut.state.value
        assertEquals(4, state.totalBicicletas)
        assertEquals(12, state.totalComponentes)
        assertEquals(1, state.totalReportesActivos)
        assertEquals(listOf("bici-1"), state.bicicletas.map { it.id })
        assertNull(state.summaryError)
    }

    @Test
    fun `mientras carga los numeros son null, no cero`() = runTest {
        // Un 0 provisorio se lee como un dato: le diría al usuario que no tiene
        // bicicletas registradas justo antes de mostrarle que sí. La pantalla
        // pinta "—" mientras el valor es null.
        val sut = viewModel(FakeDashboardApi { ResumenUsuarioDto(totalBicicletas = 4) })

        sut.loadSummary()

        assertTrue(sut.state.value.loadingSummary)
        assertNull(sut.state.value.totalBicicletas)
    }

    @Test
    fun `si el agregador falla queda el error pero la pantalla sigue viva`() = runTest {
        val sut = viewModel(FakeDashboardApi { throw httpError(500) })

        sut.loadSummary()
        advanceUntilIdle()

        val state = sut.state.value
        assertNotNull(state.summaryError)
        // Un 5xx es reintentable: no escribe nada.
        assertTrue(state.canRetrySummary)
        // Y lo importante: la carga terminó, así que la grilla se pinta igual.
        assertTrue(!state.loadingSummary)
    }

    @Test
    fun `reintentar despues de un fallo limpia el error`() = runTest {
        val api = FakeDashboardApi { throw httpError(503) }
        val sut = viewModel(api)

        sut.loadSummary()
        advanceUntilIdle()
        assertNotNull(sut.state.value.summaryError)

        api.respond = { ResumenUsuarioDto(totalBicicletas = 2) }
        sut.loadSummary()
        advanceUntilIdle()

        assertNull(sut.state.value.summaryError)
        assertEquals(2, sut.state.value.totalBicicletas)
    }

    @Test
    fun `un perfil que falla no rompe el dashboard`() = runTest {
        // El nombre del encabezado es decoración. Si `/auth/me` se cae, la
        // pantalla tiene que seguir mostrando los números.
        val sut = viewModel(
            FakeDashboardApi { ResumenUsuarioDto(totalBicicletas = 3) },
            profile = { throw httpError(500) },
        )

        sut.loadSummary()
        advanceUntilIdle()

        assertNull(sut.state.value.userName)
        assertEquals(3, sut.state.value.totalBicicletas)
    }

    // ── Baja de una bicicleta ────────────────────────────────────────────────

    @Test
    fun `dar de baja llama al backend y recarga el resumen`() = runTest {
        var llamadasAlResumen = 0
        val dashboardApi = FakeDashboardApi {
            llamadasAlResumen++
            ResumenUsuarioDto(totalBicicletas = if (llamadasAlResumen > 1) 1 else 2)
        }
        val bicycleApi = FakeBicycleApi()
        val sut = viewModel(dashboardApi, bicycleApi)

        sut.loadSummary()
        advanceUntilIdle()
        assertEquals(2, sut.state.value.totalBicicletas)

        sut.deregister("bici-1")
        advanceUntilIdle()

        assertEquals(listOf("bici-1"), bicycleApi.deleted)
        // El resumen se vuelve a pedir: los números de arriba cambian con la baja.
        assertEquals(2, llamadasAlResumen)
        assertEquals(1, sut.state.value.totalBicicletas)
        assertNotNull(sut.state.value.deregistered)
    }

    @Test
    fun `un DELETE rechazado no se lee como exito`() = runTest {
        // Retrofit no lanza cuando el tipo de retorno es Response<Unit>: sin el
        // chequeo de isSuccessful, un 403 se contaba como baja hecha.
        val bicycleApi = FakeBicycleApi {
            Response.error(403, "{}".toResponseBody("application/json".toMediaType()))
        }
        val sut = viewModel(FakeDashboardApi(), bicycleApi)

        sut.deregister("bici-1")
        advanceUntilIdle()

        assertNotNull(sut.state.value.deregisterError)
        assertNull(sut.state.value.deregistered)
    }

    @Test
    fun `no se manda la baja dos veces desde el mismo tap`() = runTest {
        val bicycleApi = FakeBicycleApi()
        val sut = viewModel(FakeDashboardApi(), bicycleApi)

        sut.deregister("bici-1")
        sut.deregister("bici-1")
        advanceUntilIdle()

        assertEquals(listOf("bici-1"), bicycleApi.deleted)
    }
}

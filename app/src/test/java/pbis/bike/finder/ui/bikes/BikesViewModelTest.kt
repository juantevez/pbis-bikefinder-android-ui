package pbis.bike.finder.ui.bikes

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.BicycleListResponseDto
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.MfaLoginRequestDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.testing.StubBicycleApi
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Provider

/**
 * El listado de bicicletas y la baja, que llegó acá desde el dashboard.
 *
 * Antes la baja era una tarjeta —"Vendí mi bici"— que abría un selector: había
 * que salir del listado y volver a reconocer la bici entre nombres parecidos.
 * Ahora se dispara sobre la fila que se está mirando. El cambio es de UI, pero
 * lo que estos tests cuidan es lo de siempre: que un `DELETE` rechazado no se
 * lea como una baja hecha, y que el mismo gesto no la mande dos veces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BikesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeBicycleApi(
        var bikes: () -> List<BicycleSummaryDto> = { emptyList() },
        var onDelete: () -> Response<Unit> = { Response.success(Unit) },
    ) : StubBicycleApi() {
        val deleted = mutableListOf<String>()

        override suspend fun list() = BicycleListResponseDto(bicycles = bikes())

        override suspend fun delete(id: String): Response<Unit> {
            deleted += id
            return onDelete()
        }
    }

    /** El perfil es decoración en esta pantalla; sólo importa que no explote. */
    private class FakeAuthApi(private val profile: () -> UserInfoDto) : AuthApi {
        override suspend fun me(): UserInfoDto = profile()

        override suspend fun login(body: LoginRequestDto) = notUsed()
        override suspend fun loginWith2fa(body: MfaLoginRequestDto) = notUsed()
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

    private fun viewModel(
        bicycleApi: FakeBicycleApi,
        profile: () -> UserInfoDto = { UserInfoDto(id = "u-1", email = "juan@example.com") },
    ): BikesViewModel {
        val authApi = FakeAuthApi(profile)
        val store = FakeTokenStore()
        return BikesViewModel(
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

    private fun bike(id: String, status: BicycleStatus? = BicycleStatus.ACTIVE) =
        BicycleSummaryDto(id = id, status = status)

    // ── Listado ──────────────────────────────────────────────────────────────

    @Test
    fun `el listado desenvuelve el wrapper del backend`() = runTest {
        val sut = viewModel(FakeBicycleApi(bikes = { listOf(bike("b1"), bike("b2")) }))

        sut.load()
        advanceUntilIdle()

        assertEquals(listOf("b1", "b2"), sut.state.value.bikes.map { it.id })
        assertNull(sut.state.value.error)
    }

    @Test
    fun `si el listado falla queda el error y se puede reintentar`() = runTest {
        val sut = viewModel(FakeBicycleApi(bikes = { throw httpError(500) }))

        sut.load()
        advanceUntilIdle()

        assertNotNull(sut.state.value.error)
        // Un 500 no escribió nada. Ojo con usar 503 acá: `isSafeToRetry` lo trata
        // distinto —lo da por inseguro salvo que el backend aconseje lo
        // contrario— porque un 503 puede llegar después de que la escritura pasó.
        assertTrue(sut.state.value.canRetry)
        assertFalse(sut.state.value.loading)
    }

    @Test
    fun `un perfil que falla no rompe el listado`() = runTest {
        // El nombre y el avatar son decoración. Si `/auth/me` se cae, las bicis
        // se tienen que ver igual.
        val sut = viewModel(
            FakeBicycleApi(bikes = { listOf(bike("b1")) }),
            profile = { throw httpError(500) },
        )

        sut.load()
        advanceUntilIdle()

        assertNull(sut.state.value.userName)
        assertEquals(1, sut.state.value.bikes.size)
    }

    // ── Baja de una bicicleta ────────────────────────────────────────────────

    @Test
    fun `dar de baja llama al backend y recarga el listado`() = runTest {
        var llamadas = 0
        val api = FakeBicycleApi(
            bikes = {
                llamadas++
                if (llamadas > 1) listOf(bike("b2")) else listOf(bike("b1"), bike("b2"))
            },
        )
        val sut = viewModel(api)

        sut.load()
        advanceUntilIdle()
        assertEquals(2, sut.state.value.bikes.size)

        sut.deregister("b1")
        advanceUntilIdle()

        assertEquals(listOf("b1"), api.deleted)
        // El listado se vuelve a pedir en vez de editarse a mano: el estado lo
        // tiene el backend.
        assertEquals(2, llamadas)
        assertEquals(listOf("b2"), sut.state.value.bikes.map { it.id })
        assertNotNull(sut.state.value.deregistered)
    }

    @Test
    fun `un DELETE rechazado no se lee como exito`() = runTest {
        // Retrofit no lanza cuando el tipo de retorno es Response<Unit>: sin el
        // chequeo de isSuccessful, un 403 se contaba como baja hecha.
        val api = FakeBicycleApi(
            onDelete = { Response.error(403, "{}".toResponseBody("application/json".toMediaType())) },
        )
        val sut = viewModel(api)

        sut.deregister("b1")
        advanceUntilIdle()

        assertNotNull(sut.state.value.deregisterError)
        assertNull(sut.state.value.deregistered)
    }

    @Test
    fun `no se manda la baja dos veces desde el mismo gesto`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)

        sut.deregister("b1")
        sut.deregister("b1")
        advanceUntilIdle()

        assertEquals(listOf("b1"), api.deleted)
    }
}

/**
 * Qué bicicletas admiten la baja.
 *
 * Esta regla vivía en `BikeAction.Sell`, cuando la baja era una tarjeta del
 * dashboard con su propio selector. Se mudó con la acción, y lo que importa de
 * ella no cambió: **es más laxa que la de denunciar o editar componentes**. Una
 * bici robada no se puede volver a denunciar ni editar, pero sí dar de baja —si
 * no, a quien sufrió el robo le queda el registro colgado para siempre, porque
 * `STOLEN` no admite ninguna otra transición.
 */
class PuedeDarseDeBajaTest {

    private fun bike(status: BicycleStatus?) = BicycleSummaryDto(id = "b1", status = status)

    @Test
    fun `una activa se puede dar de baja`() {
        assertTrue(bike(BicycleStatus.ACTIVE).puedeDarseDeBaja())
    }

    @Test
    fun `una robada tambien, que es el caso que justifica la regla`() {
        assertTrue(bike(BicycleStatus.STOLEN).puedeDarseDeBaja())
    }

    @Test
    fun `una ya vendida o inactiva no`() {
        // Ya está fuera del registro: el gesto no haría nada y el servidor lo
        // rechaza. Deslizar y que no pase nada se lee como que la app se colgó,
        // así que sobre éstas el gesto ni se habilita.
        assertFalse(bike(BicycleStatus.SOLD).puedeDarseDeBaja())
        assertFalse(bike(BicycleStatus.INACTIVE).puedeDarseDeBaja())
    }

    @Test
    fun `sin estado no se ofrece`() {
        // `status` es nullable en el DTO: nada garantiza que el backend lo mande.
        // Ante la duda no se ofrece una acción que no tiene deshacer.
        assertFalse(bike(null).puedeDarseDeBaja())
    }
}

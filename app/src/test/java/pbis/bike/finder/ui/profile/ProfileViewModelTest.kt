package pbis.bike.finder.ui.profile

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
import pbis.bike.finder.data.remote.api.GeoApi
import pbis.bike.finder.data.remote.api.NotificationApi
import pbis.bike.finder.data.remote.dto.AdminLevel1Dto
import pbis.bike.finder.data.remote.dto.AdminLevel1ListResponseDto
import pbis.bike.finder.data.remote.dto.AdminLevel2Dto
import pbis.bike.finder.data.remote.dto.AdminLevel2ListResponseDto
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.CountryDto
import pbis.bike.finder.data.remote.dto.CountryListResponseDto
import pbis.bike.finder.data.remote.dto.LocalityDto
import pbis.bike.finder.data.remote.dto.LocalityFullDto
import pbis.bike.finder.data.remote.dto.LocalityListResponseDto
import pbis.bike.finder.data.remote.dto.LocalitySearchResponseDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.MfaLoginRequestDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesRequestDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.UserLocationDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.GeoRepository
import pbis.bike.finder.data.repository.NotificationRepository
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Provider

/**
 * El perfil junta tres servicios que no se conocen entre sí, y ahí están sus dos
 * riesgos propios:
 *
 *  - **El PUT de notificaciones reemplaza el estado completo.** Mandar sólo el
 *    booleano del email le apaga WhatsApp y Telegram a quien los tenga cargados.
 *  - **La ubicación guardada hay que reconstruirla** recorriendo los cuatro
 *    niveles, porque el perfil guarda nombres y sólo el id de la localidad. Si la
 *    reconstrucción falla en silencio, el usuario abre el formulario, ve los
 *    desplegables vacíos y guarda encima una ubicación que ya tenía cargada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeAuthApi(
        var profile: () -> UserInfoDto = { UserInfoDto(id = "u-1", email = "juan@example.com") },
    ) : AuthApi {
        var lastUpdate: UpdateProfileRequestDto? = null
        var meCalls = 0

        override suspend fun me(): UserInfoDto {
            meCalls++
            return profile()
        }

        override suspend fun updateProfile(body: UpdateProfileRequestDto): UserInfoDto {
            lastUpdate = body
            return profile()
        }

        override suspend fun login(body: LoginRequestDto) = notUsed()
        override suspend fun loginWith2fa(body: MfaLoginRequestDto) = notUsed()
        override suspend fun register(body: RegisterRequestDto) = notUsed()
        override suspend fun refresh(body: RefreshTokenRequestDto): Response<AuthResponseDto> =
            notUsed()

        override suspend fun logout(body: LogoutRequestDto) = notUsed()
        override suspend fun verifyEmail(body: VerifyEmailDto) = notUsed()
        override suspend fun resendVerification(body: ResendVerificationDto) = notUsed()
        override suspend fun requestPasswordReset(body: RequestPasswordResetDto) = notUsed()
        override suspend fun confirmPasswordReset(body: ConfirmPasswordResetDto) = notUsed()

        private fun notUsed(): Nothing = throw UnsupportedOperationException()
    }

    private class FakeGeoApi(
        var countries: () -> CountryListResponseDto = {
            CountryListResponseDto(
                countries = listOf(CountryDto(id = 1, name = "Argentina", isoCode2 = "AR")),
            )
        },
    ) : GeoApi {
        private fun notUsed(): Nothing = throw UnsupportedOperationException()

        override suspend fun countries() = countries.invoke()

        override suspend fun provinces(countryId: Int) = AdminLevel1ListResponseDto(
            items = listOf(
                AdminLevel1Dto(id = 10, name = "Buenos Aires"),
                AdminLevel1Dto(id = 11, name = "Córdoba"),
            ),
        )

        override suspend fun departments(provinceId: Int) = AdminLevel2ListResponseDto(
            items = listOf(AdminLevel2Dto(id = 100, name = "La Matanza")),
        )

        override suspend fun localities(departmentId: Int) = LocalityListResponseDto(
            localities = listOf(
                LocalityDto(id = 1000, name = "Ramos Mejía"),
                LocalityDto(id = 1001, name = "San Justo"),
            ),
        )

        override suspend fun locality(localityId: Int): LocalityFullDto = notUsed()

        override suspend fun searchLocalities(query: String, countryId: Int?, limit: Int) =
            LocalitySearchResponseDto()
    }

    private class FakeNotificationApi(
        var current: NotificationPreferencesDto = NotificationPreferencesDto(
            email = "juan@example.com",
            emailEnabled = false,
            whatsappNumber = "+5491122334455",
            whatsappEnabled = true,
            anyChannelEnabled = true,
        ),
        var failOnSave: Boolean = false,
        var failOnLoad: Boolean = false,
    ) : NotificationApi {
        var lastSaved: NotificationPreferencesRequestDto? = null

        override suspend fun preferences(): NotificationPreferencesDto {
            if (failOnLoad) throw httpError(500)
            return current
        }

        override suspend fun updatePreferences(
            body: NotificationPreferencesRequestDto,
        ): NotificationPreferencesDto {
            lastSaved = body
            if (failOnSave) throw httpError(400)
            current = current.copy(
                emailEnabled = body.emailEnabled,
                whatsappNumber = body.whatsappNumber,
                whatsappEnabled = body.whatsappEnabled,
                telegramChatId = body.telegramChatId,
                telegramEnabled = body.telegramEnabled,
            )
            return current
        }
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
        authApi: FakeAuthApi = FakeAuthApi(),
        geoApi: FakeGeoApi = FakeGeoApi(),
        notificationApi: FakeNotificationApi = FakeNotificationApi(),
    ): ProfileViewModel {
        val store = FakeTokenStore()
        return ProfileViewModel(
            authRepository = AuthRepository(
                api = authApi,
                tokenStore = store,
                sessionManager = SessionManager(store, Provider { authApi }),
                json = json,
            ),
            geoRepository = GeoRepository(geoApi, json),
            notificationRepository = NotificationRepository(notificationApi, json),
        )
    }

    // ── Notificaciones ───────────────────────────────────────────────────────

    @Test
    fun `prender el email no apaga los otros canales`() = runTest(dispatcher) {
        val notifications = FakeNotificationApi()
        val vm = viewModel(notificationApi = notifications)
        advanceUntilIdle()

        vm.setEmailNotifications(true)
        advanceUntilIdle()

        val saved = notifications.lastSaved
        assertNotNull(saved)
        assertTrue(saved!!.emailEnabled)
        // El PUT reemplaza: si el WhatsApp no viaja, se apaga.
        assertEquals("+5491122334455", saved.whatsappNumber)
        assertTrue(saved.whatsappEnabled)
    }

    @Test
    fun `si el guardado falla el switch no se mueve`() = runTest(dispatcher) {
        val notifications = FakeNotificationApi(failOnSave = true)
        val vm = viewModel(notificationApi = notifications)
        advanceUntilIdle()

        vm.setEmailNotifications(true)
        advanceUntilIdle()

        // Mostrarlo prendido haría creer que se guardó algo que no se guardó.
        assertFalse(vm.state.value.notifications?.emailEnabled == true)
        assertNotNull(vm.state.value.notificationsError)
    }

    @Test
    fun `el switch queda inerte mientras no se sabe qué hay guardado`() = runTest(dispatcher) {
        val vm = viewModel(notificationApi = FakeNotificationApi(failOnLoad = true))
        advanceUntilIdle()

        assertFalse(vm.state.value.notificationsReady)
    }

    @Test
    fun `que se caiga notification-service no impide ver ni editar el perfil`() =
        runTest(dispatcher) {
            val vm = viewModel(notificationApi = FakeNotificationApi(failOnLoad = true))
            advanceUntilIdle()

            assertNotNull(vm.state.value.profile)
            assertNull(vm.state.value.loadError)
        }

    // ── Ubicación guardada ───────────────────────────────────────────────────

    @Test
    fun `la ubicación guardada queda preseleccionada en los cuatro niveles`() =
        runTest(dispatcher) {
            val authApi = FakeAuthApi {
                UserInfoDto(
                    id = "u-1",
                    email = "juan@example.com",
                    location = UserLocationDto(
                        localityId = 1000,
                        // Escrito como lo guardó el catálogo el día que se grabó:
                        // sin acentos y en mayúsculas. Tiene que coincidir igual.
                        localityName = "RAMOS MEJIA",
                        departmentName = "LA MATANZA",
                        provinceName = "BUENOS AIRES",
                        countryName = "Argentina",
                    ),
                )
            }
            val vm = viewModel(authApi = authApi)
            advanceUntilIdle()

            vm.enterEditMode()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(1, state.countryId)
            assertEquals(10, state.provinceId)
            assertEquals(100, state.departmentId)
            assertEquals(1000, state.localityId)
        }

    @Test
    fun `sin ubicación guardada no se preselecciona ningún país`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.enterEditMode()
        advanceUntilIdle()

        // Había un fallback a Argentina y era silencioso en los dos sentidos: la
        // vista decía "No especificado" mientras el formulario mostraba Argentina,
        // y a alguien cuyo país no matcheara se lo pisaba al guardar.
        assertNull(vm.state.value.countryId)
        assertNull(vm.state.value.provinceId)
    }

    @Test
    fun `con el catálogo caído la ubicación guardada no se pierde al guardar`() =
        runTest(dispatcher) {
            // `PUT /auth/me` no es un parche para la ubicación: los cuatro campos
            // se asignan siempre, así que un null los borra. Sin la guarda, abrir
            // el formulario con location-service caído y guardar cualquier otra
            // cosa vaciaba la ubicación sin un solo error a la vista.
            val authApi = FakeAuthApi {
                UserInfoDto(
                    id = "u-1",
                    email = "juan@example.com",
                    location = UserLocationDto(
                        localityId = 1000,
                        localityName = "Ramos Mejía",
                        departmentName = "La Matanza",
                        provinceName = "Buenos Aires",
                        countryName = "Argentina",
                    ),
                )
            }
            val geoApi = FakeGeoApi(countries = { throw java.io.IOException("sin red") })
            val vm = viewModel(authApi = authApi, geoApi = geoApi)
            advanceUntilIdle()

            vm.enterEditMode()
            advanceUntilIdle()
            vm.onFullNameChange("Juan Tevez")
            vm.save()
            advanceUntilIdle()

            val sent = authApi.lastUpdate!!
            assertEquals(1000, sent.localityId)
            assertEquals("Ramos Mejía", sent.localityName)
            assertEquals("La Matanza", sent.departmentName)
            assertEquals("Buenos Aires", sent.provinceName)
            assertEquals("Argentina", sent.countryName)
            // Y se dice, en vez de dejarlo pasar en silencio como hacía la web.
            assertNotNull(vm.state.value.geoError)
        }

    @Test
    fun `un nivel que sí se pudo elegir y quedó vacío viaja como null`() =
        runTest(dispatcher) {
            // Si la lista está y el usuario no eligió nada, ahí el null es la
            // respuesta correcta: pudo elegir y decidió dejarlo vacío.
            val authApi = FakeAuthApi {
                UserInfoDto(
                    id = "u-1",
                    email = "juan@example.com",
                    location = UserLocationDto(
                        localityId = 1000,
                        localityName = "Ramos Mejía",
                        countryName = "Argentina",
                    ),
                )
            }
            val vm = viewModel(authApi = authApi)
            advanceUntilIdle()

            vm.enterEditMode()
            advanceUntilIdle()
            vm.selectCountry(null)
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()

            // Y toda la cadena de abajo también: limpiar el país es limpiar la
            // ubicación, no dejarla a medias con los nombres viejos.
            assertNull(authApi.lastUpdate!!.countryName)
            assertNull(authApi.lastUpdate!!.localityName)
            assertNull(authApi.lastUpdate!!.localityId)
        }

    @Test
    fun `cambiar de provincia limpia los niveles de abajo`() = runTest(dispatcher) {
        val authApi = FakeAuthApi {
            UserInfoDto(
                id = "u-1",
                email = "juan@example.com",
                location = UserLocationDto(
                    localityId = 1000,
                    localityName = "Ramos Mejía",
                    departmentName = "La Matanza",
                    provinceName = "Buenos Aires",
                    countryName = "Argentina",
                ),
            )
        }
        val vm = viewModel(authApi = authApi)
        advanceUntilIdle()
        vm.enterEditMode()
        advanceUntilIdle()

        vm.selectProvince(11)
        advanceUntilIdle()

        // Dejar colgada la localidad de la provincia anterior guardaría una
        // ubicación que no existe.
        assertNull(vm.state.value.departmentId)
        assertNull(vm.state.value.localityId)
    }

    // ── Guardado ─────────────────────────────────────────────────────────────

    @Test
    fun `el teléfono mal formado se corta antes de la request`() = runTest(dispatcher) {
        val authApi = FakeAuthApi()
        val vm = viewModel(authApi = authApi)
        advanceUntilIdle()
        vm.enterEditMode()
        advanceUntilIdle()

        vm.onPhoneChange("1122334455")
        vm.save()
        advanceUntilIdle()

        assertNotNull(vm.state.value.phoneError)
        assertNull(authApi.lastUpdate)
        // El formulario sigue abierto: cerrarlo perdería lo que el usuario tipeó.
        assertTrue(vm.state.value.editing)
    }

    @Test
    fun `guardar manda los nombres de la jerarquía elegida`() = runTest(dispatcher) {
        val authApi = FakeAuthApi()
        val vm = viewModel(authApi = authApi)
        advanceUntilIdle()
        vm.enterEditMode()
        advanceUntilIdle()

        vm.selectCountry(1)
        advanceUntilIdle()
        vm.selectProvince(10)
        advanceUntilIdle()
        vm.selectDepartment(100)
        advanceUntilIdle()
        vm.selectLocality(1000)
        vm.onFullNameChange("  Juan Tevez  ")
        vm.onPhoneChange("+5491122334455")
        vm.save()
        advanceUntilIdle()

        val sent = authApi.lastUpdate
        assertNotNull(sent)
        // Los nombres salen de las listas cargadas, no del perfil viejo: si no,
        // cambiar de localidad guarda una ubicación mitad nueva y mitad vieja.
        assertEquals(1000, sent!!.localityId)
        assertEquals("Ramos Mejía", sent.localityName)
        assertEquals("La Matanza", sent.departmentName)
        assertEquals("Buenos Aires", sent.provinceName)
        assertEquals("Argentina", sent.countryName)
        assertEquals("Juan Tevez", sent.fullName)
        assertFalse(vm.state.value.editing)
    }

    @Test
    fun `un campo vacío viaja como null y no como cadena vacía`() = runTest(dispatcher) {
        val authApi = FakeAuthApi()
        val vm = viewModel(authApi = authApi)
        advanceUntilIdle()
        vm.enterEditMode()
        advanceUntilIdle()

        vm.onFullNameChange("   ")
        vm.save()
        advanceUntilIdle()

        assertNull(authApi.lastUpdate?.fullName)
    }

    @Test
    fun `cancelar no toca el perfil cargado`() = runTest(dispatcher) {
        val authApi = FakeAuthApi {
            UserInfoDto(id = "u-1", email = "juan@example.com", fullName = "Juan")
        }
        val vm = viewModel(authApi = authApi)
        advanceUntilIdle()
        vm.enterEditMode()

        vm.onFullNameChange("Otro nombre")
        vm.cancelEdit()

        assertFalse(vm.state.value.editing)
        assertEquals("Juan", vm.state.value.profile?.fullName)
        assertNull(authApi.lastUpdate)
    }

    @Test
    fun `el perfil se pide fresco y no del cache del login`() = runTest(dispatcher) {
        val authApi = FakeAuthApi()
        viewModel(authApi = authApi)
        advanceUntilIdle()

        // Es la pantalla donde el dato puede haber cambiado desde otro
        // dispositivo: servir el cache mostraría lo que había al entrar.
        assertEquals(1, authApi.meCalls)
    }
}

private fun httpError(code: Int) = HttpException(
    Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
)

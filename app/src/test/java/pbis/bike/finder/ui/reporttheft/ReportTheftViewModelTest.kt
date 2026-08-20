package pbis.bike.finder.ui.reporttheft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
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
import pbis.bike.finder.data.local.DevicePoint
import pbis.bike.finder.data.local.DeviceLocationProvider
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.api.GeoApi
import pbis.bike.finder.data.remote.api.NominatimAddressDto
import pbis.bike.finder.data.remote.api.NominatimApi
import pbis.bike.finder.data.remote.api.NominatimReverseDto
import pbis.bike.finder.data.remote.api.TheftReportApi
import pbis.bike.finder.data.remote.dto.AdminLevel1ListResponseDto
import pbis.bike.finder.data.remote.dto.AdminLevel2ListResponseDto
import pbis.bike.finder.data.remote.dto.AuthResponseDto
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.ConfirmPasswordResetDto
import pbis.bike.finder.data.remote.dto.CountryListResponseDto
import pbis.bike.finder.data.remote.dto.LocalityDto
import pbis.bike.finder.data.remote.dto.LocalityFullDto
import pbis.bike.finder.data.remote.dto.LocalityListResponseDto
import pbis.bike.finder.data.remote.dto.LocalitySearchResponseDto
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.LogoutRequestDto
import pbis.bike.finder.data.remote.dto.PdfGeneratedDto
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import pbis.bike.finder.data.remote.dto.RegisterRequestDto
import pbis.bike.finder.data.remote.dto.ReportStatus
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.RequestPasswordResetDto
import pbis.bike.finder.data.remote.dto.ResendVerificationDto
import pbis.bike.finder.data.remote.dto.TheftReportDto
import pbis.bike.finder.data.remote.dto.TheftReportListResponseDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.remote.dto.VerifyEmailDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.GeoRepository
import pbis.bike.finder.data.repository.GeocodingRepository
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.testing.StubBicycleApi
import pbis.bike.finder.testing.StubTheftReportApi
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Provider

/**
 * La denuncia es la pantalla donde equivocarse cuesta más caro: el backend
 * persiste el reporte antes de los pasos best-effort, así que una denuncia mal
 * armada no se arregla reintentando —devuelve "ya existe un reporte activo"—.
 *
 * Por eso estos tests miran sobre todo **qué se manda** y **qué se rechaza antes
 * de mandarlo**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportTheftViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeBicycleApi : StubBicycleApi() {
        var lastReport: ReportTheftRequestDto? = null
        var reportResponse: () -> TheftReportDto = { TheftReportDto(id = "reporte-1") }

        override suspend fun detail(id: String) = BicycleDto(id = id)

        override suspend fun reportTheft(id: String, body: ReportTheftRequestDto): TheftReportDto {
            lastReport = body
            return reportResponse()
        }
    }

    private class FakeGeoApi(
        var countries: () -> CountryListResponseDto = { CountryListResponseDto() },
        var localities: () -> LocalityListResponseDto = { LocalityListResponseDto() },
        var search: () -> LocalitySearchResponseDto = { LocalitySearchResponseDto() },
    ) : GeoApi {
        override suspend fun countries() = countries.invoke()
        override suspend fun provinces(countryId: Int) = AdminLevel1ListResponseDto()
        override suspend fun departments(provinceId: Int) = AdminLevel2ListResponseDto()
        override suspend fun localities(departmentId: Int) = localities.invoke()
        override suspend fun searchLocalities(query: String, countryId: Int?, limit: Int) =
            search.invoke()
    }

    private class FakeNominatimApi(
        var respond: () -> NominatimReverseDto = {
            NominatimReverseDto(
                address = NominatimAddressDto(
                    road = "Av. 7",
                    houseNumber = "1234",
                    city = "La Plata",
                ),
            )
        },
    ) : NominatimApi {
        override suspend fun reverse(
            lat: Double,
            lon: Double,
            format: String,
            zoom: Int,
            addressDetails: Int,
            language: String,
        ) = respond()
    }

    private class FakeTheftApi(
        var pdf: () -> PdfGeneratedDto = { PdfGeneratedDto(presignedUrl = "https://s3/pdf") },
    ) : StubTheftReportApi() {
        /** Las denuncias que el backend ya tiene. Por defecto, ninguna. */
        var reports: List<TheftReportDto> = emptyList()
        var myReportsCalls = 0

        override suspend fun generatePdf(reportId: String) = pdf()

        override suspend fun myReports(): TheftReportListResponseDto {
            myReportsCalls++
            return TheftReportListResponseDto(reports = reports, total = reports.size)
        }
    }

    private class FakeLocationProvider(var point: DevicePoint?) : DeviceLocationProvider {
        override suspend fun currentPoint() = point
    }

    private class FakeTokenStore : TokenStorage {
        override val hasSession: Flow<Boolean> get() = flowOf(true)
        override suspend fun accessToken() = "access"
        override suspend fun refreshToken() = "refresh"
        override suspend fun save(accessToken: String, refreshToken: String) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeAuthApi : AuthApi {
        override suspend fun me() = UserInfoDto(
            id = "u-1",
            email = "juan@example.com",
            phoneNumber = "1122334455",
        )

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

    private val json = Json { ignoreUnknownKeys = true }

    private fun viewModel(
        bicycleApi: FakeBicycleApi = FakeBicycleApi(),
        theftApi: FakeTheftApi = FakeTheftApi(),
        geoApi: FakeGeoApi = FakeGeoApi(),
        nominatimApi: FakeNominatimApi = FakeNominatimApi(),
        location: DevicePoint? = null,
    ): ReportTheftViewModel {
        val authApi = FakeAuthApi()
        val store = FakeTokenStore()
        return ReportTheftViewModel(
            theftRepository = TheftRepository(bicycleApi, theftApi, json),
            bicycleRepository = BicycleRepository(bicycleApi, json),
            geoRepository = GeoRepository(geoApi, json),
            geocodingRepository = GeocodingRepository(nominatimApi, json),
            authRepository = AuthRepository(
                api = authApi,
                tokenStore = store,
                sessionManager = SessionManager(store, Provider { authApi }),
                json = json,
            ),
            locationProvider = FakeLocationProvider(location),
        )
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Unit>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    // ── Ubicación obligatoria ────────────────────────────────────────────────

    @Test
    fun `sin ubicacion no se envia nada`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.submit()
        advanceUntilIdle()

        // Lo que importa no es el mensaje: es que la request no salió. Enviarla
        // y que el backend la rechace sería lo mismo sólo si el rechazo llegara
        // siempre antes del commit, y esa garantía no la tiene el cliente.
        assertNull(api.lastReport)
        assertNotNull(sut.state.value.fieldErrors["ubicacion"])
    }

    @Test
    fun `la localidad sola alcanza como ubicacion`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(42)
        sut.submit()
        advanceUntilIdle()

        assertEquals(42, api.lastReport?.theftLocation?.localityId)
        assertNull(api.lastReport?.theftLocation?.latitude)
    }

    @Test
    fun `el punto del telefono viaja junto con la localidad`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api, location = DevicePoint(-34.9214, -57.9544))
        sut.start("bici-1")
        advanceUntilIdle()

        sut.useCurrentLocation()
        advanceUntilIdle()
        sut.selectLocality(42)
        sut.submit()
        advanceUntilIdle()

        assertEquals(-34.9214, api.lastReport?.theftLocation?.latitude)
        assertEquals(-57.9544, api.lastReport?.theftLocation?.longitude)
        // "EXACT" está reservado a las pistas: acá el punto es del teléfono de
        // quien denuncia, no del lugar donde se vio la bici.
        assertEquals("APPROXIMATE", api.lastReport?.theftLocation?.precision)
    }

    @Test
    fun `el punto solo ya no alcanza, la localidad es obligatoria`() = runTest {
        // El backend acepta una denuncia con solo el punto, pero los dos PDF
        // derivan provincia y localidad de localityId y no hay otra fuente: sin
        // ella, el informe que se lleva a la policía sale sin jurisdicción.
        // Medido en un e2e: un robo en Colegiales salió con la calle correcta y
        // con "Provincia: -" y "Localidad: -".
        val api = FakeBicycleApi()
        val sut = viewModel(api, location = DevicePoint(-34.9214, -57.9544))
        sut.start("bici-1")
        advanceUntilIdle()

        sut.useCurrentLocation()
        advanceUntilIdle()
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport)
        assertNotNull(sut.state.value.fieldErrors["ubicacion"])
        // El pedido nombra lo que falta: el usuario ya marcó el punto y cree que
        // terminó con la ubicación.
        assertTrue(sut.state.value.fieldErrors["ubicacion"]!!.contains("localidad"))
    }

    @Test
    fun `una calle sin localidad ni punto no cuenta como ubicacion`() = runTest {
        // Es el caso que más fácil se cuela: el usuario escribe la calle y cree
        // que ya dijo dónde fue. El backend lo rechaza igual, así que dejarlo
        // pasar sólo agrega un viaje perdido.
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setStreetName("Av. 7")
        sut.setStreetNumber("1234")
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport)
        assertNotNull(sut.state.value.fieldErrors["ubicacion"])
    }

    @Test
    fun `si falla el GPS el formulario sigue siendo usable`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api, location = null)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.useCurrentLocation()
        advanceUntilIdle()

        assertNotNull(sut.state.value.locationError)
        assertTrue(!sut.state.value.locating)

        // Y la localidad sigue sirviendo para completar la denuncia.
        sut.selectLocality(7)
        sut.submit()
        advanceUntilIdle()
        assertEquals(7, api.lastReport?.theftLocation?.localityId)
    }

    @Test
    fun `si location-service esta caido se dice, no quedan desplegables vacios`() = runTest {
        // Era un bug real: la cascada se tragaba el error y "no hay datos" se
        // veía igual que "no se pudo preguntar". Con los desplegables vacíos, el
        // usuario no tenía forma de elegir localidad ni de saber por qué.
        val geoApi = FakeGeoApi(countries = { throw httpError(503) })
        val sut = viewModel(geoApi = geoApi)
        sut.start("bici-1")
        advanceUntilIdle()

        assertNotNull(sut.state.value.geoError)
        assertTrue(sut.state.value.countries.isEmpty())
    }

    @Test
    fun `elegir localidad centra el mapa sin marcar el punto`() = runTest {
        val geoApi = FakeGeoApi(
            localities = {
                LocalityListResponseDto(
                    localities = listOf(
                        LocalityDto(id = 9, name = "La Plata", latitude = -34.92, longitude = -57.95),
                    ),
                )
            },
        )
        val sut = viewModel(geoApi = geoApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectDepartment(1)
        advanceUntilIdle()
        sut.selectLocality(9)

        assertEquals(-34.92 to -57.95, sut.state.value.centerOn)
        // Centrar la cámara no es marcar dónde fue el robo: el marcador sigue
        // siendo del usuario.
        assertNull(sut.state.value.latitude)
    }

    @Test
    fun `un error de validacion se ve tambien junto al boton`() = runTest {
        // El botón está al final de un formulario largo y el error de ubicación
        // se pinta media pantalla más arriba: sin este resumen, apretar
        // "Presentar la denuncia" parecía no hacer nada.
        val sut = viewModel()
        sut.start("bici-1")
        advanceUntilIdle()

        sut.submit()
        advanceUntilIdle()

        assertNotNull(sut.state.value.formError)
    }

    // ── Mapa y dirección ─────────────────────────────────────────────────────

    @Test
    fun `tocar el mapa marca el punto`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        // La localidad es obligatoria desde que los PDF dependen de ella: sin
        // esto el envío se corta en la validación y el punto nunca viaja.
        sut.selectLocality(42)
        sut.submit()
        advanceUntilIdle()

        assertEquals(-34.9214, api.lastReport?.theftLocation?.latitude)
    }

    @Test
    fun `la direccion resuelta no viaja hasta que se confirma`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.resolveAddress()
        advanceUntilIdle()

        // Propuesta en pantalla, pero todavía no copiada a los campos.
        assertNotNull(sut.state.value.resolvedAddress)
        assertEquals("", sut.state.value.streetName)

        sut.applyResolvedAddress()

        assertEquals("7", sut.state.value.streetName)
        assertEquals(StreetType.AVENIDA, sut.state.value.streetType)
        assertEquals("1234", sut.state.value.streetNumber)
        assertNull(sut.state.value.resolvedAddress)
    }

    /**
     * El caso que motivó todo esto: marcar el punto en el mapa producía una
     * denuncia sin `localityId`, y el PDF público —que sólo muestra
     * provincia/partido/localidad— salía sin ninguna ubicación.
     */
    @Test
    fun `confirmar la direccion del mapa tambien fija la localidad`() = runTest {
        val geoApi = FakeGeoApi(search = { searchResults(LA_PLATA) })
        val sut = viewModel(geoApi = geoApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.resolveAddress()
        advanceUntilIdle()

        // Propuesta, no aplicada: el usuario todavía no dijo que sí.
        assertEquals(9, sut.state.value.resolvedLocality?.id)
        assertNull(sut.state.value.localityId)

        sut.applyResolvedAddress()
        advanceUntilIdle()

        assertEquals(9, sut.state.value.localityId)
        // La cascada queda coherente: si no, el desplegable de provincia se ve
        // vacío y tocarlo borra la localidad recién elegida.
        assertEquals(1, sut.state.value.provinceId)
        assertEquals(3, sut.state.value.departmentId)
    }

    @Test
    fun `con punto y sin localidad se avisa que falta, y la denuncia no es valida`() = runTest {
        val sut = viewModel()
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)

        // Antes esto era una denuncia válida con un cartel público pobre. Ahora
        // es un formulario incompleto: el aviso explica por qué, y la validación
        // lo bloquea.
        assertTrue(!sut.state.value.hasLocation)
        assertTrue(sut.state.value.faltaLocalidadConPunto)
    }

    @Test
    fun `elegir la localidad apaga el aviso`() = runTest {
        val geoApi = FakeGeoApi(
            localities = {
                LocalityListResponseDto(localities = listOf(LocalityDto(id = 9, name = "La Plata")))
            },
        )
        val sut = viewModel(geoApi = geoApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.selectDepartment(3)
        advanceUntilIdle()
        sut.selectLocality(9)

        assertTrue(!sut.state.value.faltaLocalidadConPunto)
        assertTrue(sut.state.value.hasLocation)
    }

    @Test
    fun `un homonimo sin provincia que lo desempate no se propone`() {
        // Hay una Belgrano por provincia. Proponer la equivocada es peor que no
        // proponer: el usuario confirma sin releer y la denuncia queda en otro lado.
        val resultados = listOf(
            LA_PLATA.copy(id = 20, name = "Belgrano"),
            LA_PLATA.copy(id = 21, name = "Belgrano"),
        )

        assertNull(resultados.bestMatch("Belgrano", provinceName = null))
        // Dos en la misma provincia tampoco alcanza para decidir.
        assertNull(resultados.bestMatch("Belgrano", "Buenos Aires"))
    }

    @Test
    fun `una coincidencia parcial no se propone`() {
        // El backend busca por substring: "Morón" trae también "Villa Morón".
        val resultados = listOf(LA_PLATA.copy(id = 30, name = "Villa Morón"))

        assertNull(resultados.bestMatch("Morón", "Buenos Aires"))
    }

    @Test
    fun `el match ignora acentos y mayusculas`() {
        // OSM escribe "Ramos Mejía"; el catálogo, "RAMOS MEJIA".
        val resultados = listOf(LA_PLATA.copy(id = 31, name = "RAMOS MEJIA"))

        assertEquals(31, resultados.bestMatch("Ramos Mejía", "Buenos Aires")?.id)
    }

    @Test
    fun `entre homonimos gana el de la provincia que dijo OSM`() {
        val resultados = listOf(
            LA_PLATA.copy(id = 40, name = "Belgrano"),
            LA_PLATA.copy(
                id = 41,
                name = "Belgrano",
                adminLevel1 = LocalityFullDto.AdminLevel1InfoDto(id = 2, name = "Santa Fe"),
            ),
        )

        assertEquals(41, resultados.bestMatch("Belgrano", "Santa Fe")?.id)
    }

    @Test
    fun `descartar la direccion deja el punto marcado`() = runTest {
        // Un punto sin dirección textual es un estado válido: las coordenadas y
        // la calle son datos distintos.
        val sut = viewModel()
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.resolveAddress()
        advanceUntilIdle()
        sut.discardResolvedAddress()

        assertNull(sut.state.value.resolvedAddress)
        assertEquals(-34.9214, sut.state.value.latitude)
    }

    @Test
    fun `mover el punto descarta la direccion que se habia resuelto`() = runTest {
        // La dirección era del punto anterior. Dejarla en pantalla invita a
        // confirmar una calle que ya no corresponde al marcador.
        val sut = viewModel()
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.resolveAddress()
        advanceUntilIdle()
        assertNotNull(sut.state.value.resolvedAddress)

        sut.setPoint(-34.6037, -58.3816)

        assertNull(sut.state.value.resolvedAddress)
    }

    @Test
    fun `si Nominatim falla el punto sigue marcado`() = runTest {
        val nominatim = FakeNominatimApi(respond = { throw httpError(429) })
        val sut = viewModel(nominatimApi = nominatim)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.setPoint(-34.9214, -57.9544)
        sut.resolveAddress()
        advanceUntilIdle()

        assertNotNull(sut.state.value.geocodingError)
        assertEquals(-34.9214, sut.state.value.latitude)
    }

    // ── Payload ──────────────────────────────────────────────────────────────

    @Test
    fun `el contacto se precarga del perfil`() = runTest {
        val sut = viewModel()
        sut.start("bici-1")
        advanceUntilIdle()

        assertEquals("juan@example.com", sut.state.value.contactEmail)
        assertEquals("1122334455", sut.state.value.contactPhone)
    }

    @Test
    fun `los campos vacios viajan como null y no como cadena vacia`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.setDescription("   ")
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport?.theftDescription)
        assertNull(api.lastReport?.theftTimeApprox)
    }

    @Test
    fun `la franja horaria y la moneda viajan con el codigo del front web`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.setTimeSlot(TheftTimeSlot.AFTERNOON)
        sut.setRewardOffered(true)
        sut.setRewardAmount("5000")
        sut.setRewardCurrency(RewardCurrency.USD)
        sut.submit()
        advanceUntilIdle()

        assertEquals("AFTERNOON", api.lastReport?.theftTimeApprox)
        assertEquals("USD", api.lastReport?.rewardCurrency)
    }

    @Test
    fun `sin recompensa no viaja monto ni moneda`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        // El usuario la activa, escribe algo y se arrepiente: los valores no
        // pueden quedar colgados en el payload.
        sut.setRewardOffered(true)
        sut.setRewardAmount("5000")
        sut.setRewardOffered(false)
        sut.submit()
        advanceUntilIdle()

        assertEquals(false, api.lastReport?.rewardOffered)
        assertNull(api.lastReport?.rewardAmount)
        assertNull(api.lastReport?.rewardCurrency)
    }

    @Test
    fun `una recompensa con monto invalido no se envia`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.setRewardOffered(true)
        sut.setRewardAmount("mil pesos")
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport)
        assertNotNull(sut.state.value.fieldErrors["recompensa"])
    }

    @Test
    fun `no se acepta una fecha futura`() = runTest {
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.setDate(LocalDate(2099, 1, 1))
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport)
        assertNotNull(sut.state.value.fieldErrors["fecha"])
    }

    // ── Después de crear ─────────────────────────────────────────────────────

    @Test
    fun `no se puede enviar dos veces la misma denuncia`() = runTest {
        // Reintentar después del éxito es exactamente lo que produce el
        // "An active theft report already exists for this bicycle" del backend.
        val api = FakeBicycleApi()
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()
        api.lastReport = null

        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastReport)
        assertEquals("reporte-1", sut.state.value.createdReportId)
    }

    @Test
    fun `si falla el PDF la denuncia sigue creada`() = runTest {
        val theftApi = FakeTheftApi(pdf = { throw httpError(500) })
        val sut = viewModel(theftApi = theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        sut.downloadPdf()
        advanceUntilIdle()

        val state = sut.state.value
        assertEquals("reporte-1", state.createdReportId)
        assertNotNull(state.pdfError)
        // El texto tiene que decir que la denuncia quedó hecha: confundir "no
        // salió el PDF" con "no se denunció" es el peor malentendido posible acá.
        assertTrue(state.pdfError!!.contains("denuncia ya quedó hecha"))
        assertNull(state.pdfUrl)
    }

    @Test
    fun `un fallo del envio deja el formulario listo para corregir`() = runTest {
        val api = FakeBicycleApi()
        api.reportResponse = { throw httpError(500) }
        val sut = viewModel(api)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        assertNotNull(state.formError)
        assertNull(state.createdReportId)
        assertTrue(!state.submitting)
    }

    // ── Envio ambiguo: el 503 con la denuncia ya creada ──────────────────────

    @Test
    fun `un 503 con la denuncia ya creada se resuelve como exito`() = runTest {
        // El caso medido en un e2e real: geoposicion tardo 16s, el gateway corta
        // a los 10 y devuelve 503, pero el backend commitea la denuncia antes de
        // sus pasos best-effort — la denuncia quedo hecha.
        val bicycleApi = FakeBicycleApi()
        bicycleApi.reportResponse = { throw httpError(503) }
        val theftApi = FakeTheftApi().apply {
            reports = listOf(
                TheftReportDto(id = "reporte-9", bicycleId = "bici-1", status = ReportStatus.ACTIVE),
            )
        }

        val sut = viewModel(bicycleApi, theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        // No se le muestra un error por algo que salio bien.
        assertNull(state.formError)
        assertEquals("reporte-9", state.createdReportId)
    }

    @Test
    fun `un duplicado rechazado tampoco se muestra como error`() = runTest {
        // Reintentar despues del 503 devolvia "An active theft report already
        // exists for this bicycle": en ingles, en rojo, describiendo como falla
        // algo que en realidad fue un exito.
        val bicycleApi = FakeBicycleApi()
        bicycleApi.reportResponse = { throw httpError(409) }
        val theftApi = FakeTheftApi().apply {
            reports = listOf(
                TheftReportDto(id = "reporte-9", bicycleId = "bici-1", status = ReportStatus.ACTIVE),
            )
        }

        val sut = viewModel(bicycleApi, theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        assertEquals("reporte-9", sut.state.value.createdReportId)
        assertNull(sut.state.value.formError)
    }

    @Test
    fun `si de verdad no se creo nada, el error se muestra`() = runTest {
        val bicycleApi = FakeBicycleApi()
        bicycleApi.reportResponse = { throw httpError(503) }
        val theftApi = FakeTheftApi()   // sin denuncias

        val sut = viewModel(bicycleApi, theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        assertNotNull(sut.state.value.formError)
        assertNull(sut.state.value.createdReportId)
        assertEquals(1, theftApi.myReportsCalls)
    }

    @Test
    fun `una denuncia de OTRA bici no cuenta como propia`() = runTest {
        val bicycleApi = FakeBicycleApi()
        bicycleApi.reportResponse = { throw httpError(503) }
        val theftApi = FakeTheftApi().apply {
            reports = listOf(
                TheftReportDto(id = "otra", bicycleId = "bici-2", status = ReportStatus.ACTIVE),
            )
        }

        val sut = viewModel(bicycleApi, theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        assertNull(sut.state.value.createdReportId)
        assertNotNull(sut.state.value.formError)
    }

    @Test
    fun `una denuncia ya cerrada no bloquea ni se confunde con la nueva`() = runTest {
        // Una bici recuperada puede volver a robarse: sus denuncias FOUND o
        // CLOSED no son la que se acaba de intentar.
        val bicycleApi = FakeBicycleApi()
        bicycleApi.reportResponse = { throw httpError(503) }
        val theftApi = FakeTheftApi().apply {
            reports = listOf(
                TheftReportDto(id = "vieja", bicycleId = "bici-1", status = ReportStatus.FOUND),
                TheftReportDto(id = "cerrada", bicycleId = "bici-1", status = ReportStatus.CLOSED),
            )
        }

        val sut = viewModel(bicycleApi, theftApi)
        sut.start("bici-1")
        advanceUntilIdle()

        sut.selectLocality(1)
        sut.submit()
        advanceUntilIdle()

        assertNull(sut.state.value.createdReportId)
        assertNotNull(sut.state.value.formError)
    }

    // ── Fixtures del catálogo de localidades ─────────────────────────────────

    /** Una localidad con la jerarquía completa, como la devuelve la búsqueda. */
    private val LA_PLATA = LocalityFullDto(
        id = 9,
        name = "La Plata",
        latitude = -34.92,
        longitude = -57.95,
        fullName = "La Plata, Buenos Aires, Argentina",
        adminLevel2 = LocalityFullDto.AdminLevel2InfoDto(id = 3, name = "La Plata"),
        adminLevel1 = LocalityFullDto.AdminLevel1InfoDto(id = 1, name = "Buenos Aires"),
    )

    private fun searchResults(vararg items: LocalityFullDto) =
        LocalitySearchResponseDto(results = items.toList(), total = items.size)
}

package pbis.bike.finder.ui.addbike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleListResponseDto
import pbis.bike.finder.data.remote.dto.BikeTypeDto
import pbis.bike.finder.data.remote.dto.BrandDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDetailsDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDto
import pbis.bike.finder.data.remote.dto.ColorDto
import pbis.bike.finder.data.remote.dto.ColorwayDto
import pbis.bike.finder.data.remote.dto.FrameSizeDto
import pbis.bike.finder.data.remote.dto.InitialFormDataDto
import pbis.bike.finder.data.remote.dto.PhotoListResponseDto
import pbis.bike.finder.data.remote.dto.PhotoUploadResponseDto
import pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto
import pbis.bike.finder.data.remote.dto.RegisterManuallyRequestDto
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.TheftReportDto
import pbis.bike.finder.data.remote.dto.UpdateComponentsRequestDto
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.CatalogRepository
import pbis.bike.finder.data.repository.PendingPhoto
import pbis.bike.finder.data.repository.PhotoUploadOutcome
import pbis.bike.finder.data.repository.PhotoUploader
import retrofit2.Response

/**
 * El wizard de alta es casi todo encadenamiento: elegir una cosa invalida la
 * siguiente. Estos tests cubren los reseteos, que es donde el front web tuvo un
 * bug real y silencioso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddBikeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Dobles ───────────────────────────────────────────────────────────────

    private val brands = listOf(BrandDto(id = 1, name = "Trek"), BrandDto(id = 2, name = "Giant"))
    private val bikeTypes = listOf(
        BikeTypeDto(id = 10, name = "MTB", sizeSystemId = 100),
        BikeTypeDto(id = 11, name = "Ruta", sizeSystemId = null),
    )
    private val colors = listOf(ColorDto(id = 5, name = "Black", nameEs = "Negro"))

    private val marlin = CatalogBikeDto(id = 1000, modelName = "Marlin 7", modelYear = 2023)
    private val roscoe = CatalogBikeDto(id = 1001, modelName = "Roscoe 8", modelYear = 2024)

    private fun detailsFor(bikeId: Long) = CatalogBikeDetailsDto(
        bike = CatalogBikeDto(id = bikeId),
        colorways = listOf(
            ColorwayDto(id = bikeId * 10, colorwayName = "Rojo", isDefault = false),
            ColorwayDto(id = bikeId * 10 + 1, colorwayName = "Azul", isDefault = true),
        ),
        availableSizes = listOf(
            FrameSizeDto(id = 1, sizeCode = "M", sizeLabel = "Medium"),
            FrameSizeDto(id = 2, sizeCode = "L", sizeLabel = "Large"),
        ),
    )

    /** Sólo implementa lo que el wizard usa. */
    private open class FakeBicycleApi : BicycleApi {
        var lastCatalogRequest: RegisterFromCatalogRequestDto? = null
        var lastManualRequest: RegisterManuallyRequestDto? = null
        var formData = InitialFormDataDto()
        var bikesByBrand: List<CatalogBikeDto> = emptyList()
        var details: CatalogBikeDetailsDto? = null
        var sizes: List<FrameSizeDto> = emptyList()
        var lastBikeTypeFilter: Long? = null

        override suspend fun catalogFormData() = formData
        override suspend fun catalogBikesByBrand(brandId: Long, bikeTypeId: Long?) =
            bikesByBrand.also { lastBikeTypeFilter = bikeTypeId }

        override suspend fun catalogBikeDetails(catalogBikeId: Long) = details!!
        override suspend fun catalogSizes(sizeSystemId: Long) = sizes

        override suspend fun registerFromCatalog(body: RegisterFromCatalogRequestDto): BicycleDto {
            lastCatalogRequest = body
            return BicycleDto(id = "nueva-bici")
        }

        override suspend fun registerManually(body: RegisterManuallyRequestDto): BicycleDto {
            lastManualRequest = body
            return BicycleDto(id = "nueva-bici")
        }

        override suspend fun list() = BicycleListResponseDto()
        override suspend fun detail(id: String) = notUsed()
        override suspend fun delete(id: String): Response<Unit> = notUsed()
        override suspend fun updateComponents(
            id: String,
            body: UpdateComponentsRequestDto,
        ): Response<Unit> = notUsed()

        override suspend fun photos(id: String): PhotoListResponseDto = notUsed()
        override suspend fun uploadPhoto(
            id: String,
            file: okhttp3.MultipartBody.Part,
            photoType: okhttp3.RequestBody,
            setAsPrimary: okhttp3.RequestBody,
            gpsAnalysisConsent: okhttp3.RequestBody,
        ): PhotoUploadResponseDto = notUsed()

        override suspend fun reportTheft(
            id: String,
            body: ReportTheftRequestDto,
        ): TheftReportDto = notUsed()

        protected fun notUsed(): Nothing = throw UnsupportedOperationException()
    }

    /** Devuelve el resultado que se le configure, sin tocar disco ni red. */
    private class FakeUploader(
        private val outcome: PhotoUploadOutcome = PhotoUploadOutcome(0, 0),
    ) : PhotoUploader {
        var lastConsent: Boolean? = null
        var lastPhotos: List<PendingPhoto> = emptyList()

        override suspend fun uploadAll(
            bicycleId: String,
            photos: List<PendingPhoto>,
            gpsAnalysisConsent: Boolean,
        ): PhotoUploadOutcome {
            lastConsent = gpsAnalysisConsent
            lastPhotos = photos
            return outcome
        }
    }

    private fun viewModel(
        api: FakeBicycleApi,
        uploader: PhotoUploader = FakeUploader(),
    ): AddBikeViewModel {
        val json = Json { ignoreUnknownKeys = true }
        return AddBikeViewModel(
            CatalogRepository(api, json),
            BicycleRepository(api, json),
            uploader,
        )
    }

    private fun apiWithCatalog() = FakeBicycleApi().apply {
        formData = InitialFormDataDto(
            frameBrands = brands,
            bikeTypes = bikeTypes,
            colors = colors,
        )
        bikesByBrand = listOf(marlin, roscoe)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `cambiar de modelo descarta el colorway del modelo anterior`() = runTest {
        // Es el bug que el front web tuvo: los colorways pertenecen a UN modelo.
        // Sin el reseteo se manda un colorwayId ajeno, el backend lo descarta en
        // silencio y la bici queda guardada con color "unknown".
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onBrandSelected(1)
        advanceUntilIdle()

        api.details = detailsFor(1000)
        sut.onModelSelected(1000)
        advanceUntilIdle()
        val colorwayDelPrimero = sut.state.value.colorwayId
        assertEquals(10001L, colorwayDelPrimero) // el marcado isDefault

        api.details = detailsFor(1001)
        sut.onModelSelected(1001)
        advanceUntilIdle()

        // El colorway nuevo pertenece al modelo nuevo, no arrastra el anterior.
        assertEquals(10011L, sut.state.value.colorwayId)
    }

    @Test
    fun `el colorway por defecto es el marcado, no el primero de la lista`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()
        api.details = detailsFor(1000)

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.onModelSelected(1000)
        advanceUntilIdle()

        // "Azul" está segundo pero tiene isDefault = true.
        assertEquals(10001L, sut.state.value.colorwayId)
    }

    @Test
    fun `cambiar de marca limpia modelo, colorway y talle`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()
        api.details = detailsFor(1000)

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.onModelSelected(1000)
        advanceUntilIdle()
        sut.onFrameSizeSelected("M")

        sut.onBrandSelected(2)
        advanceUntilIdle()

        val state = sut.state.value
        assertNull(state.catalogBikeId)
        assertNull(state.colorwayId)
        assertNull(state.frameSize)
        assertTrue(state.colorways.isEmpty())
        assertTrue(state.availableSizes.isEmpty())
    }

    @Test
    fun `el tipo filtra los modelos en modo catalogo`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onBrandSelected(1)
        advanceUntilIdle()
        assertNull(api.lastBikeTypeFilter)

        sut.onBikeTypeSelected(10)
        advanceUntilIdle()

        assertEquals(10L, api.lastBikeTypeFilter)
    }

    @Test
    fun `en modo manual el tipo trae los talles de su sistema`() = runTest {
        // En manual no hay modelo del cual sacar talles: salen del sizeSystemId
        // del tipo. Son dos fuentes distintas para el mismo dato.
        val api = apiWithCatalog()
        api.sizes = listOf(FrameSizeDto(id = 9, sizeCode = "S", sizeLabel = "Small"))
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.setMode(AddBikeMode.MANUAL)
        sut.onBikeTypeSelected(10)
        advanceUntilIdle()

        assertEquals(listOf("S"), sut.state.value.manualSizes.map { it.sizeCode })
    }

    @Test
    fun `un tipo sin sistema de talles no ofrece talles`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.setMode(AddBikeMode.MANUAL)
        sut.onBikeTypeSelected(11) // sizeSystemId = null
        advanceUntilIdle()

        assertTrue(sut.state.value.manualSizes.isEmpty())
    }

    @Test
    fun `el color personalizado y el de la lista se excluyen`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onPrimaryColorSelected(5)
        sut.onPrimaryColorCustomChange("Verde flúor")

        // Mandar los dos deja al backend decidir cuál gana, que es como se
        // guardan bicis con un color que el dueño no eligió.
        assertNull(sut.state.value.primaryColorId)
        assertEquals("Verde flúor", sut.state.value.primaryColorCustom)

        sut.onPrimaryColorSelected(5)
        assertEquals("", sut.state.value.primaryColorCustom)
    }

    @Test
    fun `el alta manual exige marca y color`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.setMode(AddBikeMode.MANUAL)
        sut.submit()
        advanceUntilIdle()

        val errors = sut.state.value.fieldErrors
        assertTrue(errors.containsKey(AddBikeViewModel.FIELD_BRAND))
        assertTrue(errors.containsKey(AddBikeViewModel.FIELD_COLOR))
        // No se mandó nada: la validación corta antes del viaje.
        assertNull(api.lastManualRequest)
    }

    @Test
    fun `el alta desde catalogo exige modelo`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.submit()
        advanceUntilIdle()

        assertTrue(sut.state.value.fieldErrors.containsKey(AddBikeViewModel.FIELD_MODEL))
        assertNull(api.lastCatalogRequest)
    }

    @Test
    fun `los campos de texto vacios se mandan como null y no como cadena vacia`() = runTest {
        // "" y null no significan lo mismo para el backend: una cadena vacía es un
        // número de serie vacío guardado, null es "no hay dato".
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()
        api.details = detailsFor(1000)

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.onModelSelected(1000)
        advanceUntilIdle()
        sut.onSerialNumberChange("   ")
        sut.submit()
        advanceUntilIdle()

        assertNull(api.lastCatalogRequest!!.serialNumber)
        assertNull(api.lastCatalogRequest!!.notes)
    }

    @Test
    fun `el alta exitosa expone el id de la bici creada`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()
        api.details = detailsFor(1000)

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.onModelSelected(1000)
        advanceUntilIdle()
        sut.onFrameSizeSelected("M")
        sut.submit()
        advanceUntilIdle()

        assertEquals("nueva-bici", sut.state.value.createdBikeId)
        assertEquals("M", api.lastCatalogRequest!!.frameSize)
        assertEquals(1000L, api.lastCatalogRequest!!.catalogBikeId)
    }

    @Test
    fun `un fallo del backend no marca la bici como creada`() = runTest {
        val api = object : FakeBicycleApi() {
            override suspend fun registerFromCatalog(
                body: RegisterFromCatalogRequestDto,
            ): BicycleDto = throw java.io.IOException("sin red")
        }.apply {
            formData = InitialFormDataDto(frameBrands = brands, bikeTypes = bikeTypes)
            bikesByBrand = listOf(marlin)
            details = detailsFor(1000)
        }
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onBrandSelected(1)
        advanceUntilIdle()
        sut.onModelSelected(1000)
        advanceUntilIdle()
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        assertNull(state.createdBikeId)
        // Se libera el botón: el usuario se queda en la pantalla y puede reintentar.
        assertTrue(!state.submitting)
        assertEquals(
            "No se pudo conectar con el servidor. Revisá tu conexión.",
            state.formError,
        )
    }

    /** URI de mentira: [PendingPhoto] la guarda como texto justamente para esto. */
    private fun uri(id: String): String = "content://test/$id"

    // ── Fotos ────────────────────────────────────────────────────────────────

    private suspend fun altaListaParaEnviar(
        api: FakeBicycleApi,
        sut: AddBikeViewModel,
        advance: suspend () -> Unit,
    ) {
        api.details = detailsFor(1000)
        sut.onBrandSelected(1)
        advance()
        sut.onModelSelected(1000)
        advance()
    }

    @Test
    fun `sin fotos el alta navega directo`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()
        altaListaParaEnviar(api, sut) { advanceUntilIdle() }

        sut.submit()
        advanceUntilIdle()

        assertEquals("nueva-bici", sut.state.value.createdBikeId)
        assertNull(sut.state.value.photoWarning)
    }

    @Test
    fun `una foto que no sube NO invalida el alta`() = runTest {
        // La bicicleta ya existe cuando las fotos se suben. Decirle al usuario que
        // el registro falló porque no entró una foto sería mentirle.
        val api = apiWithCatalog()
        val uploader = FakeUploader(PhotoUploadOutcome(uploaded = 1, failed = 1))
        val sut = viewModel(api, uploader)
        advanceUntilIdle()
        altaListaParaEnviar(api, sut) { advanceUntilIdle() }

        sut.onPhotosPicked(listOf(uri("a"), uri("b")))
        sut.submit()
        advanceUntilIdle()

        val state = sut.state.value
        assertEquals("nueva-bici", state.createdBikeId)
        assertNull(state.formError)
        assertTrue(state.photoWarning!!.contains("1 de 2"))
    }

    @Test
    fun `el consentimiento GPS viaja tal como lo dejo el usuario`() = runTest {
        val api = apiWithCatalog()
        val uploader = FakeUploader()
        val sut = viewModel(api, uploader)
        advanceUntilIdle()
        altaListaParaEnviar(api, sut) { advanceUntilIdle() }

        sut.onPhotosPicked(listOf(uri("a")))
        sut.onGpsConsentChanged(true)
        sut.submit()
        advanceUntilIdle()

        // Sin marcar, media-service no publica el GPS hacia fraud-detection: es
        // una decisión del usuario, no un default que el cliente pueda cambiar.
        assertEquals(true, uploader.lastConsent)
    }

    @Test
    fun `la primera foto queda como principal`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onPhotosPicked(listOf(uri("a"), uri("b"), uri("c")))

        val photos = sut.state.value.photos
        assertEquals(listOf(true, false, false), photos.map { it.isPrimary })
    }

    @Test
    fun `quitar la principal promueve a la siguiente`() = runTest {
        // Quedarse sin foto principal deja la bici sin imagen en el listado.
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onPhotosPicked(listOf(uri("a"), uri("b")))
        sut.onPhotoRemoved(uri("a"))

        val photos = sut.state.value.photos
        assertEquals(1, photos.size)
        assertTrue(photos.first().isPrimary)
    }

    @Test
    fun `agregar mas fotos no cambia la principal ya elegida`() = runTest {
        val api = apiWithCatalog()
        val sut = viewModel(api)
        advanceUntilIdle()

        sut.onPhotosPicked(listOf(uri("a")))
        sut.onPhotosPicked(listOf(uri("b"), uri("c")))

        assertEquals(listOf(true, false, false), sut.state.value.photos.map { it.isPrimary })
    }
}
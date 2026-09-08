package pbis.bike.finder.ui.updatecomponents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.PhotoDto
import pbis.bike.finder.data.remote.dto.PhotoListResponseDto
import pbis.bike.finder.data.remote.dto.PhotoType
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.CatalogRepository
import pbis.bike.finder.data.repository.PendingPhoto
import pbis.bike.finder.data.repository.PhotoUploadOutcome
import pbis.bike.finder.data.repository.PhotoUploadResult
import pbis.bike.finder.data.repository.PhotoUploader
import pbis.bike.finder.testing.StubBicycleApi
import retrofit2.Response

/**
 * La sección de fotos de "Actualizar componentes".
 *
 * Lo que cuidan estos tests no es la grilla sino las tres reglas que no se ven
 * mirándola: que el tope se aplique **antes** de subir nada, que una subida
 * cortada no se cuente como fallida —el servidor pudo haberla guardado— y que
 * lo que queda en pantalla lo diga el backend y no la aritmética del cliente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BikePhotosTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── El aviso de una tanda ────────────────────────────────────────────────

    @Test
    fun `sin nada que decir no hay aviso`() {
        assertNull(uploadMessage(subidas = 0, rechazadas = 0, inciertas = 0, sobran = 0))
    }

    @Test
    fun `una subida cortada no se anuncia como fallida`() {
        val message = uploadMessage(subidas = 0, rechazadas = 0, inciertas = 1, sobran = 0)!!

        // Decir "no se pudo subir" sobre una foto que puede estar guardada lleva
        // al usuario a reintentar y a terminar con la foto repetida.
        assertFalse(message.contains("no se pudo"))
        assertTrue(message.contains("fijate en la lista"))
    }

    @Test
    fun `las fotos que no entraron se cuentan aparte de las que fallaron`() {
        val message = uploadMessage(subidas = 1, rechazadas = 1, inciertas = 0, sobran = 2)!!

        assertTrue(message.contains("1 foto(s) agregada(s)."))
        assertTrue(message.contains("1 no se pudo(ieron) subir."))
        assertTrue(message.contains("2 no entró(aron): el máximo es $MAX_FOTOS."))
    }

    // ── El tope ──────────────────────────────────────────────────────────────

    @Test
    fun `la lista se corta antes de subir, no despues`() = runTest(dispatcher) {
        val api = FakeApi(fotos = listOf(foto("1"), foto("2")))
        val uploader = FakeUploader()
        val vm = viewModel(api, uploader)
        vm.start("bici-1")
        advanceUntilIdle()

        vm.onPhotosPicked(listOf("uri-a", "uri-b", "uri-c"))
        advanceUntilIdle()

        // Quedaban dos lugares: se suben dos y la tercera no viaja. Subir y
        // avisar después deja al usuario sin saber cuáles quedaron.
        assertEquals(2, uploader.subidas.size)
        assertTrue(vm.state.value.photoMessage!!.contains("1 no entró(aron)"))
    }

    @Test
    fun `con el tope lleno no se sube nada y se dice como hacer lugar`() = runTest(dispatcher) {
        val api = FakeApi(fotos = (1..MAX_FOTOS).map { foto("$it") })
        val uploader = FakeUploader()
        val vm = viewModel(api, uploader)
        vm.start("bici-1")
        advanceUntilIdle()

        vm.onPhotosPicked(listOf("uri-a"))
        advanceUntilIdle()

        assertTrue(uploader.subidas.isEmpty())
        assertTrue(vm.state.value.photoMessage!!.contains("Eliminá una"))
    }

    @Test
    fun `la foto nueva viaja con el tipo y el consentimiento elegidos`() = runTest(dispatcher) {
        val api = FakeApi(fotos = emptyList())
        val uploader = FakeUploader()
        val vm = viewModel(api, uploader)
        vm.start("bici-1")
        advanceUntilIdle()

        vm.onPhotoTypeChanged(PhotoType.SERIAL_NUMBER)
        vm.onGpsConsentChanged(true)
        vm.onPhotosPicked(listOf("uri-a"))
        advanceUntilIdle()

        assertEquals(PhotoType.SERIAL_NUMBER, uploader.subidas.single().photoType)
        assertEquals(true, uploader.ultimoConsentimiento)
        // Agregar una foto no decide cuál es la principal de la bici.
        assertFalse(uploader.subidas.single().isPrimary)
    }

    // ── Qué queda en pantalla ────────────────────────────────────────────────

    @Test
    fun `despues de subir, la grilla la dicta el backend`() = runTest(dispatcher) {
        val api = FakeApi(fotos = emptyList())
        // La subida se corta, pero del otro lado quedó guardada.
        val uploader = FakeUploader(resultado = PhotoUploadResult.INCIERTA)
        val vm = viewModel(api, uploader)
        vm.start("bici-1")
        advanceUntilIdle()

        api.fotos = listOf(foto("nueva"))
        vm.onPhotosPicked(listOf("uri-a"))
        advanceUntilIdle()

        assertEquals(listOf("nueva"), vm.state.value.photos.map { it.id })
    }

    @Test
    fun `un borrado rechazado no saca la foto de la grilla`() = runTest(dispatcher) {
        val api = FakeApi(fotos = listOf(foto("1")), borrado = { error409() })
        val vm = viewModel(api, FakeUploader())
        vm.start("bici-1")
        advanceUntilIdle()

        vm.confirmDelete(vm.state.value.photos.single())
        vm.deleteConfirmedPhoto()
        advanceUntilIdle()

        assertEquals(listOf("1"), vm.state.value.photos.map { it.id })
        assertFalse(vm.state.value.photosBusy)
    }

    @Test
    fun `borrar pide confirmacion antes de tocar el backend`() = runTest(dispatcher) {
        val api = FakeApi(fotos = listOf(foto("1")))
        val vm = viewModel(api, FakeUploader())
        vm.start("bici-1")
        advanceUntilIdle()

        vm.confirmDelete(vm.state.value.photos.single())
        advanceUntilIdle()

        assertTrue(api.borradas.isEmpty())

        vm.dismissDelete()
        vm.deleteConfirmedPhoto()
        advanceUntilIdle()

        // Sin confirmación vigente no hay nada que borrar: el diálogo es el
        // único camino, y es permanente del otro lado.
        assertTrue(api.borradas.isEmpty())
    }

    @Test
    fun `una foto sin clave no se dibuja`() = runTest(dispatcher) {
        val api = FakeApi(fotos = listOf(foto("1"), PhotoDto(id = "2")))
        val vm = viewModel(api, FakeUploader())
        vm.start("bici-1")
        advanceUntilIdle()

        // Sin downloadUrl ni thumbnailUrl no hay nada que pedirle a
        // media-service: una tarjeta rota no le sirve a nadie.
        assertEquals(listOf("1"), vm.state.value.photos.map { it.id })
    }

    // ── Dobles ───────────────────────────────────────────────────────────────

    private class FakeApi(
        var fotos: List<PhotoDto>,
        private val borrado: () -> Response<Unit> = { Response.success(Unit) },
    ) : StubBicycleApi() {
        val borradas = mutableListOf<String>()

        override suspend fun detail(id: String): BicycleDto = BicycleDto(id = id)

        override suspend fun photos(id: String) = PhotoListResponseDto(photos = fotos)

        override suspend fun deletePhoto(id: String): Response<Unit> {
            borradas += id
            return borrado()
        }
    }

    private class FakeUploader(
        private val resultado: PhotoUploadResult = PhotoUploadResult.OK,
    ) : PhotoUploader {
        val subidas = mutableListOf<PendingPhoto>()
        var ultimoConsentimiento: Boolean? = null

        override suspend fun uploadAll(
            bicycleId: String,
            photos: List<PendingPhoto>,
            gpsAnalysisConsent: Boolean,
        ): PhotoUploadOutcome = throw UnsupportedOperationException()

        override suspend fun uploadOne(
            bicycleId: String,
            photo: PendingPhoto,
            gpsAnalysisConsent: Boolean,
        ): PhotoUploadResult {
            subidas += photo
            ultimoConsentimiento = gpsAnalysisConsent
            return resultado
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun viewModel(api: FakeApi, uploader: PhotoUploader) = UpdateComponentsViewModel(
        bicycleRepository = BicycleRepository(api, json),
        catalogRepository = CatalogRepository(api, json),
        photoUploader = uploader,
    )

    private fun foto(id: String) = PhotoDto(id = id, downloadUrl = "images/$id.jpg")

    private fun error409(): Response<Unit> = Response.error(
        409,
        "".toResponseBody("application/json".toMediaType()),
    )
}

package pbis.bike.finder.ui.reports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import pbis.bike.finder.data.remote.dto.BicycleListResponseDto
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.data.remote.dto.PdfGeneratedDto
import pbis.bike.finder.data.remote.dto.ReportStatus
import pbis.bike.finder.data.remote.dto.TheftReportDto
import pbis.bike.finder.data.remote.dto.TheftReportListResponseDto
import pbis.bike.finder.data.remote.dto.UnreadTipsCountDto
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.testing.StubBicycleApi
import pbis.bike.finder.testing.StubTheftReportApi
import retrofit2.HttpException
import retrofit2.Response

/**
 * La pantalla existe porque el PDF era inalcanzable: el botón vivía sólo en la
 * pantalla de éxito de la denuncia, y el PDF público no lo generaba nadie.
 *
 * Por eso los tests miran sobre todo que **los dos documentos sean alcanzables**
 * y que un fallo secundario no se lleve puesta la lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyReportsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeTheftApi(
        var reports: () -> TheftReportListResponseDto = { TheftReportListResponseDto() },
        var unread: () -> UnreadTipsCountDto = { UnreadTipsCountDto() },
    ) : StubTheftReportApi() {
        var privateCalls = 0
        var publicCalls = 0

        override suspend fun myReports() = reports.invoke()
        override suspend fun unreadTipsCount() = unread.invoke()

        override suspend fun generatePdf(reportId: String): PdfGeneratedDto {
            privateCalls++
            return PdfGeneratedDto(presignedUrl = "https://s3/privado")
        }

        override suspend fun generatePublicPdf(reportId: String): PdfGeneratedDto {
            publicCalls++
            return PdfGeneratedDto(presignedUrl = "https://s3/publico")
        }
    }

    private class FakeBicycleApi(
        var bikes: () -> BicycleListResponseDto = { BicycleListResponseDto() },
    ) : StubBicycleApi() {
        override suspend fun list() = bikes.invoke()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun viewModel(
        theftApi: FakeTheftApi = FakeTheftApi(),
        bicycleApi: FakeBicycleApi = FakeBicycleApi(),
    ) = MyReportsViewModel(
        theftRepository = TheftRepository(bicycleApi, theftApi, json),
        bicycleRepository = BicycleRepository(bicycleApi, json),
    )

    private fun unReporte() = TheftReportListResponseDto(
        reports = listOf(
            TheftReportDto(
                id = "rep-1",
                bicycleId = "bici-1",
                status = ReportStatus.ACTIVE,
                theftDate = LocalDate(2026, 8, 13),
            ),
        ),
        total = 1,
    )

    private fun unaBici() = BicycleListResponseDto(
        bicycles = listOf(
            BicycleSummaryDto(id = "bici-1", brandName = "Specialized", model = "Stumpjumper"),
        ),
    )

    @Test
    fun `la denuncia se muestra con la bici y no con un UUID`() = runTest {
        val sut = viewModel(FakeTheftApi(reports = ::unReporte), FakeBicycleApi(bikes = ::unaBici))
        advanceUntilIdle()

        val row = sut.state.value.reports.single()
        assertEquals("Specialized Stumpjumper", row.bikeLabel)
        assertEquals(LocalDate(2026, 8, 13), row.theftDate)
    }

    @Test
    fun `los dos PDF son alcanzables desde la misma denuncia`() = runTest {
        // Es la razón de ser de la pantalla: antes el privado sólo existía en la
        // pantalla de éxito y el público no lo generaba nadie.
        val api = FakeTheftApi(reports = ::unReporte)
        val sut = viewModel(api, FakeBicycleApi(bikes = ::unaBici))
        advanceUntilIdle()

        sut.downloadPdf("rep-1", publicVersion = false)
        advanceUntilIdle()
        assertEquals("https://s3/privado", sut.state.value.pdfUrl)

        sut.onPdfOpened()
        sut.downloadPdf("rep-1", publicVersion = true)
        advanceUntilIdle()
        assertEquals("https://s3/publico", sut.state.value.pdfUrl)

        assertEquals(1, api.privateCalls)
        assertEquals(1, api.publicCalls)
    }

    @Test
    fun `la URL se consume una sola vez`() = runTest {
        // Si quedara en el estado, volver a la pantalla reabriría el visor solo.
        val sut = viewModel(FakeTheftApi(reports = ::unReporte))
        advanceUntilIdle()

        sut.downloadPdf("rep-1", publicVersion = true)
        advanceUntilIdle()
        assertNotNull(sut.state.value.pdfUrl)

        sut.onPdfOpened()
        assertNull(sut.state.value.pdfUrl)
    }

    @Test
    fun `si falla el contador de pistas la lista igual se muestra`() = runTest {
        // Quien entra a buscar el PDF no puede quedarse sin él porque falló un badge.
        val api = FakeTheftApi(
            reports = ::unReporte,
            unread = { throw HttpException(Response.error<Any>(500, "{}".toResponseBody(JSON))) },
        )
        val sut = viewModel(api, FakeBicycleApi(bikes = ::unaBici))
        advanceUntilIdle()

        assertEquals(1, sut.state.value.reports.size)
        assertNull(sut.state.value.error)
        assertEquals(0, sut.state.value.reports.single().unreadTips)
    }

    @Test
    fun `si falla el listado de bicis la denuncia se muestra igual`() = runTest {
        val sut = viewModel(FakeTheftApi(reports = ::unReporte), FakeBicycleApi(bikes = {
            throw HttpException(Response.error<Any>(500, "{}".toResponseBody(JSON)))
        }))
        advanceUntilIdle()

        // Sin marca ni modelo, pero con sus PDF: es lo que el usuario vino a buscar.
        assertEquals("Bicicleta", sut.state.value.reports.single().bikeLabel)
    }

    @Test
    fun `el badge de pistas sin leer sale del contador`() = runTest {
        val api = FakeTheftApi(
            reports = ::unReporte,
            unread = { UnreadTipsCountDto(total = 3, porReporte = mapOf("rep-1" to 3)) },
        )
        val sut = viewModel(api, FakeBicycleApi(bikes = ::unaBici))
        advanceUntilIdle()

        assertEquals(3, sut.state.value.reports.single().unreadTips)
    }

    @Test
    fun `sin denuncias se distingue el vacio del error`() = runTest {
        val sut = viewModel()
        advanceUntilIdle()

        assertTrue(sut.state.value.isEmpty)
        assertNull(sut.state.value.error)
    }

    @Test
    fun `si no se pueden cargar las denuncias se dice, y no se muestra vacio`() = runTest {
        val sut = viewModel(
            FakeTheftApi(reports = {
                throw HttpException(Response.error<Any>(503, "{}".toResponseBody(JSON)))
            }),
        )
        advanceUntilIdle()

        assertNotNull(sut.state.value.error)
        // "No hay denuncias" y "no pude preguntar" no se pueden ver igual.
        assertTrue(!sut.state.value.isEmpty)
    }

    @Test
    fun `cambiar de pestaña no vuelve a pedir las denuncias`() = runTest {
        var llamadas = 0
        val api = FakeTheftApi(reports = { llamadas++; unReporte() })
        val sut = viewModel(api, FakeBicycleApi(bikes = ::unaBici))
        advanceUntilIdle()

        sut.selectTab(ReportsTab.TIPS)
        advanceUntilIdle()

        assertEquals(ReportsTab.TIPS, sut.state.value.tab)
        assertEquals(1, llamadas)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

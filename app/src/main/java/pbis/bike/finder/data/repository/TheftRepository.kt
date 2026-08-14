package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.api.TheftReportApi
import pbis.bike.finder.data.remote.dto.PdfGeneratedDto
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.TheftReportDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Denuncias de robo.
 *
 * El alta cuelga de la bicicleta (`bike-registration` la rutea a theft-report) y
 * el resto vive en theft-report, de ahí las dos interfaces.
 */
@Singleton
class TheftRepository @Inject constructor(
    private val bicycleApi: BicycleApi,
    private val theftApi: TheftReportApi,
    private val json: Json,
) {
    /**
     * Crea la denuncia.
     *
     * **No es una operación reintentable a ciegas.** Del lado del backend son
     * tres pasos: el primero persiste el reporte y marca la bici `STOLEN` en una
     * transacción que commitea antes de cualquier I/O remota; los otros dos
     * —registrar la ubicación en geoposicion y publicar el evento en Kafka— son
     * best-effort. A partir del paso 1 la denuncia existe aunque el usuario vea
     * un error, y reintentar devuelve "An active theft report already exists for
     * this bicycle".
     */
    suspend fun reportTheft(
        bicycleId: String,
        body: ReportTheftRequestDto,
    ): ApiResult<TheftReportDto> = apiCall(json) { bicycleApi.reportTheft(bicycleId, body) }

    /**
     * Genera el PDF de la denuncia y devuelve una URL prefirmada de S3. El
     * documento no se arma en el cliente.
     */
    suspend fun generatePdf(reportId: String): ApiResult<PdfGeneratedDto> =
        apiCall(json) { theftApi.generatePdf(reportId) }

    /**
     * El cartel público, con el QR para que un tercero reporte una pista.
     *
     * Son **dos documentos distintos**, no dos formatos del mismo: el privado
     * lleva la calle, la hora, la descripción y el contacto —es el que va a la
     * policía o al seguro—; el público omite todo eso y sólo muestra la zona.
     * Por eso los dos aparecen juntos en la pantalla: elegir cuál compartir es
     * una decisión del usuario y tiene que ser visible.
     */
    suspend fun generatePublicPdf(reportId: String): ApiResult<PdfGeneratedDto> =
        apiCall(json) { theftApi.generatePublicPdf(reportId) }

    /** Las denuncias del usuario, para la pantalla de reportes y pistas. */
    suspend fun myReports(): ApiResult<List<TheftReportDto>> =
        apiCall(json) { theftApi.myReports().reports }

    /**
     * Cuántas pistas sin leer tiene cada denuncia.
     *
     * Una sola request para todos los badges: el backend devuelve **sólo** las
     * denuncias con pistas nuevas, así que las ausentes valen cero.
     */
    suspend fun unreadTipCounts(): ApiResult<Map<String, Int>> =
        apiCall(json) { theftApi.unreadTipsCount().porReporte }
}

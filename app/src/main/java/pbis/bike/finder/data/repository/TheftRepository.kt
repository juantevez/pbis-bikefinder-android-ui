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
}

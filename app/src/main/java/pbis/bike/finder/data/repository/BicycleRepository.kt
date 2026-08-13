package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BicycleRepository @Inject constructor(
    private val api: BicycleApi,
    private val json: Json,
) {
    /**
     * Listado del usuario.
     *
     * Desenvuelve el `{ bicycles, total }` del backend: el `total` no aporta
     * nada que la lista no diga, y arrastrarlo hasta la UI obligaría a que cada
     * pantalla sepa la forma del wrapper.
     */
    suspend fun myBicycles(): ApiResult<List<BicycleSummaryDto>> =
        when (val result = apiCall(json) { api.list() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.bicycles)
            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }

    /**
     * Detalle. **No es el mismo modelo que el resumen**: acá marca, modelo y
     * año vienen anidados en `frame`.
     *
     * Las fotos no se piden acá: van por media-service, que en el entorno de
     * desarrollo mínimo no está levantado.
     */
    suspend fun bicycle(id: String): ApiResult<BicycleDto> =
        apiCall(json) { api.detail(id) }
}

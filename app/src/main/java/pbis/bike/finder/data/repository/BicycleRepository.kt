package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.orThrow
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.data.remote.dto.PhotoDto
import pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto
import pbis.bike.finder.data.remote.dto.RegisterManuallyRequestDto
import pbis.bike.finder.data.remote.dto.UpdateComponentsRequestDto
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

    suspend fun registerFromCatalog(
        body: RegisterFromCatalogRequestDto,
    ): ApiResult<BicycleDto> = apiCall(json) { api.registerFromCatalog(body) }

    suspend fun registerManually(
        body: RegisterManuallyRequestDto,
    ): ApiResult<BicycleDto> = apiCall(json) { api.registerManually(body) }

    /**
     * Fotos de la bici.
     *
     * El detalle ya trae un `photos`, pero sin tipar y sin la garantía de estar
     * completo; el front web también lo ignora y repide. Se desenvuelve el
     * `{ photos, total }` por la misma razón que el listado de bicis.
     */
    suspend fun photos(id: String): ApiResult<List<PhotoDto>> =
        when (val result = apiCall(json) { api.photos(id) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.photos)
            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }

    /**
     * Reemplaza el mapa de componentes.
     *
     * El `components` que llega ya viene con la metadata de procedencia
     * calculada; armarlo es responsabilidad de quien conoce el estado anterior y
     * lo que tocó el usuario. Ver `buildComponentsPayload`.
     *
     * No devuelve cuerpo, así que el status de error hay que levantarlo a mano:
     * ver [orThrow].
     */
    suspend fun updateComponents(id: String, components: JsonObject): ApiResult<Unit> =
        apiCall(json) {
            api.updateComponents(id, UpdateComponentsRequestDto(components)).orThrow()
        }
}

/**
 * URL para bajar una foto, a partir del `downloadUrl` que devuelve media-service.
 *
 * Ese campo **no es una URL**: es la clave del archivo en el bucket, y hay que
 * pasarla por `/api/files/download?fileKey=…`. Usarla directamente como origen
 * de una imagen no carga nada.
 *
 * El host es el mismo placeholder que usa Retrofit —lo reescribe
 * `provideBaseUrlInterceptor` con el backend configurado— así que esta URL sólo
 * sirve sobre el OkHttp de la app, que es justamente el que tiene el ImageLoader.
 */
fun photoDownloadUrl(fileKey: String): String =
    "http://localhost/api/files/download?fileKey=" +
        java.net.URLEncoder.encode(fileKey, "UTF-8")

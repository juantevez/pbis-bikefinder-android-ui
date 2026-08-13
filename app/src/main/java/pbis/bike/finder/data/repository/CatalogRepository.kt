package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.CatalogBikeDetailsDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDto
import pbis.bike.finder.data.remote.dto.FrameSizeDto
import pbis.bike.finder.data.remote.dto.InitialFormDataDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catálogo de referencia: marcas, tipos, colores, modelos, colorways y talles.
 *
 * Es el candidato más claro a caché local (Room): no cambia entre sesiones y hoy
 * se vuelve a pedir en cada apertura del wizard. Por ahora se cachea sólo el
 * bootstrap en memoria, que es lo que evita el pedido repetido dentro de una
 * misma sesión sin traer una base de datos a la fase.
 *
 * `/api/v1/catalog/…` es público en el gateway, así que esto funciona incluso
 * antes de que el usuario tenga sesión — se podría precargar en el arranque.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: BicycleApi,
    private val json: Json,
) {
    @Volatile
    private var cachedFormData: InitialFormDataDto? = null

    suspend fun formData(): ApiResult<InitialFormDataDto> {
        cachedFormData?.let { return ApiResult.Success(it) }

        return apiCall(json) { api.catalogFormData() }
            .also { if (it is ApiResult.Success) cachedFormData = it.data }
    }

    /**
     * Modelos de una marca, opcionalmente filtrados por tipo.
     *
     * El front web filtra además por año; acá no se expone todavía porque no hay
     * UI que lo use, y un parámetro sin control que lo maneje es sólo superficie.
     */
    suspend fun bikesByBrand(
        brandId: Long,
        bikeTypeId: Long? = null,
    ): ApiResult<List<CatalogBikeDto>> =
        apiCall(json) { api.catalogBikesByBrand(brandId, bikeTypeId) }

    /** Detalle del modelo: trae sus colorways y sus talles disponibles. */
    suspend fun bikeDetails(catalogBikeId: Long): ApiResult<CatalogBikeDetailsDto> =
        apiCall(json) { api.catalogBikeDetails(catalogBikeId) }

    /**
     * Talles de un sistema de talles.
     *
     * Sólo para el alta **manual**, donde no hay modelo de catálogo del cual
     * sacarlos: el `sizeSystemId` sale del tipo de bici elegido. En el alta desde
     * catálogo los talles vienen dentro de [bikeDetails] — son dos fuentes
     * distintas para el mismo dato, y confundirlas ofrece talles que ese modelo
     * no tiene.
     */
    suspend fun sizesForSystem(sizeSystemId: Long): ApiResult<List<FrameSizeDto>> =
        apiCall(json) { api.catalogSizes(sizeSystemId) }
}

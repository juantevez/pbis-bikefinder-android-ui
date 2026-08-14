package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.GeoApi
import pbis.bike.finder.data.remote.dto.AdminLevel1Dto
import pbis.bike.finder.data.remote.dto.AdminLevel2Dto
import pbis.bike.finder.data.remote.dto.CountryDto
import pbis.bike.finder.data.remote.dto.LocalityDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jerarquía geográfica: país → provincia → departamento → localidad.
 *
 * Cuatro niveles, una request por nivel. Son endpoints públicos —los usa también
 * el formulario de pistas, donde no hay sesión—, así que van sin token; eso ya
 * lo declara [GeoApi] con `HEADER_SKIP_AUTH`.
 *
 * Cada método desenvuelve su wrapper: los tres últimos vienen en `items` y el
 * primero en `countries`, una asimetría del backend que no tiene por qué llegar
 * a la UI.
 */
@Singleton
class GeoRepository @Inject constructor(
    private val api: GeoApi,
    private val json: Json,
) {
    suspend fun countries(): ApiResult<List<CountryDto>> =
        apiCall(json) { api.countries().countries }

    suspend fun provinces(countryId: Int): ApiResult<List<AdminLevel1Dto>> =
        apiCall(json) { api.provinces(countryId).items }

    suspend fun departments(provinceId: Int): ApiResult<List<AdminLevel2Dto>> =
        apiCall(json) { api.departments(provinceId).items }

    suspend fun localities(departmentId: Int): ApiResult<List<LocalityDto>> =
        apiCall(json) { api.localities(departmentId).items }
}

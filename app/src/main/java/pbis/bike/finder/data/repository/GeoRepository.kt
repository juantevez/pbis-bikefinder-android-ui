package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.GeoApi
import pbis.bike.finder.data.remote.dto.AdminLevel1Dto
import pbis.bike.finder.data.remote.dto.AdminLevel2Dto
import pbis.bike.finder.data.remote.dto.CountryDto
import pbis.bike.finder.data.remote.dto.LocalityDto
import pbis.bike.finder.data.remote.dto.LocalityFullDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jerarquía geográfica: país → provincia → departamento → localidad.
 *
 * Cuatro niveles, una request por nivel. Son endpoints públicos —los usa también
 * el formulario de pistas, donde no hay sesión—, así que van sin token; eso ya
 * lo declara [GeoApi] con `HEADER_SKIP_AUTH`.
 *
 * Cada método desenvuelve su wrapper, y los cuatro nombran la lista distinto:
 * `countries`, `items`, `items`, `localities`. Es una asimetría del backend que
 * no tiene por qué llegar a la UI —pero conviene mirar el nombre real antes de
 * agregar un nivel: leer el campo equivocado devuelve una lista vacía en vez de
 * un error.
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
        apiCall(json) { api.localities(departmentId).localities }

    /**
     * Una localidad con su jerarquía completa.
     *
     * Rearma la cascada al corregir una denuncia: el reporte guarda sólo el
     * `localityId` y esto dice qué elegir en los tres niveles de arriba.
     */
    suspend fun locality(localityId: Int): ApiResult<LocalityFullDto> =
        apiCall(json) { api.locality(localityId) }

    /**
     * Busca localidades por nombre, con la jerarquía completa en cada resultado.
     *
     * Es lo que permite convertir el "Ramos Mejía" que devuelve OSM en el
     * `localityId` que espera la denuncia, sin encadenar los cuatro niveles.
     */
    suspend fun searchLocalities(
        query: String,
        countryId: Int? = null,
        limit: Int = 20,
    ): ApiResult<List<LocalityFullDto>> =
        apiCall(json) { api.searchLocalities(query, countryId, limit).results }
}

package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.DashboardApi
import pbis.bike.finder.data.remote.dto.ResumenUsuarioDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardRepository @Inject constructor(
    private val api: DashboardApi,
    private val json: Json,
) {
    /**
     * Resumen del agregador. De todo lo que devuelve, la pantalla hoy sólo usa
     * la lista de bicicletas, que alimenta los selectores: la tira de números
     * del encabezado se sacó del dashboard.
     *
     * Los contadores se siguen parseando igual, y dos cosas que el nombre de los
     * campos no dice vienen del backend:
     * `totalBicicletas` sólo cuenta las `ACTIVE` y `STOLEN` —las vendidas o dadas
     * de baja no suman—, y `totalReportesActivos` cuenta bicicletas distintas, no
     * denuncias: dos denuncias de la misma bici valen una.
     */
    suspend fun summary(): ApiResult<ResumenUsuarioDto> = apiCall(json) { api.userSummary() }
}

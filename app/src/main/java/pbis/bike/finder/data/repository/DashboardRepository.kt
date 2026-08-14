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
     * Resumen del agregador: los cuatro números del encabezado y la lista de
     * bicicletas que alimenta los selectores.
     *
     * Dos cosas que el nombre de los campos no dice, y que vienen del backend:
     * `totalBicicletas` sólo cuenta las `ACTIVE` y `STOLEN` —las vendidas o dadas
     * de baja no suman—, y `totalReportesActivos` cuenta bicicletas distintas, no
     * denuncias: dos denuncias de la misma bici valen una.
     */
    suspend fun summary(): ApiResult<ResumenUsuarioDto> = apiCall(json) { api.userSummary() }
}

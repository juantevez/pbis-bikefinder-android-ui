package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.NominatimApi
import pbis.bike.finder.data.remote.api.NominatimReverseDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Una dirección resuelta a partir de un punto, ya normalizada al vocabulario del
 * backend.
 *
 * `streetType` sale del prefijo de la calle que devuelve OSM, con la misma tabla
 * que usa el front web.
 */
data class ResolvedAddress(
    val streetType: String?,
    val streetName: String?,
    val streetNumber: String?,
    /** Para mostrar: "Av. 7 1234, La Plata". */
    val display: String?,
)

@Singleton
class GeocodingRepository @Inject constructor(
    private val api: NominatimApi,
    private val json: Json,
) {
    suspend fun reverse(latitude: Double, longitude: Double): ApiResult<ResolvedAddress> =
        when (val result = apiCall(json) { api.reverse(latitude, longitude) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.toResolvedAddress())
            is ApiResult.NoNetwork -> ApiResult.NoNetwork
            is ApiResult.HttpError -> result
            is ApiResult.Malformed -> result
        }
}

private fun NominatimReverseDto.toResolvedAddress(): ResolvedAddress {
    val road = address?.road?.trim()
    val (type, name) = parseStreet(road)

    return ResolvedAddress(
        streetType = type,
        streetName = name,
        streetNumber = address?.houseNumber?.trim(),
        display = listOfNotNull(
            listOfNotNull(road, address?.houseNumber).joinToString(" ").ifBlank { null },
            address?.city ?: address?.town ?: address?.village ?: address?.suburb,
        ).joinToString(", ").ifBlank { displayName },
    )
}

/**
 * Separa "Av. 7" en tipo de vía y nombre.
 *
 * OSM devuelve el tipo pegado al nombre y el backend los quiere separados. La
 * tabla es la del front web; lo que no matchea es `CALLE`, que es lo que
 * abrumadoramente más hay.
 */
internal fun parseStreet(road: String?): Pair<String?, String?> {
    if (road.isNullOrBlank()) return null to null

    val prefixes = listOf(
        "AVENIDA" to listOf("avenida ", "av. ", "av "),
        "BOULEVARD" to listOf("boulevard ", "bulevar ", "blvd. ", "blvd "),
        "DIAGONAL" to listOf("diagonal ", "diag. ", "diag "),
        "PASAJE" to listOf("pasaje ", "pje. ", "pje "),
    )

    val lower = road.lowercase()
    for ((type, forms) in prefixes) {
        val match = forms.firstOrNull { lower.startsWith(it) } ?: continue
        return type to road.substring(match.length).trim().ifBlank { null }
    }

    return "CALLE" to road
}

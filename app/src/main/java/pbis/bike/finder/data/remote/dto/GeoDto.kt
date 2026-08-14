package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// location-service — com.bikefinder.location.application.dto.LocationDto
//
// Jerarquía de cuatro niveles: país → level1 (provincia) → level2
// (departamento/partido) → localidad. Sin autenticación.
//
// Cada nivel es una request, así que llenar un domicilio son cuatro viajes.
// Junto con el catálogo, es lo primero que conviene cachear.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class CountryListResponseDto(
    val countries: List<CountryDto> = emptyList(),
    val total: Int = 0,
)

/**
 * Los niveles 1 y 2 comparten forma de wrapper: `items` + `total`, más el id del
 * padre (`countryId`, etc.) que el cliente ya conoce.
 *
 * **Las localidades no**: ver [LocalityListResponseDto].
 */
@Serializable
data class AdminLevel1ListResponseDto(
    val items: List<AdminLevel1Dto> = emptyList(),
    val total: Int = 0,
    val countryId: Int? = null,
)

@Serializable
data class AdminLevel2ListResponseDto(
    val items: List<AdminLevel2Dto> = emptyList(),
    val total: Int = 0,
)

/**
 * El wrapper de localidades rompe el patrón: la lista viene en `localities`, no
 * en `items` (`LocationDto.LocalityListResponse` del backend).
 *
 * Leerlo como `items` no fallaba: el default deja la lista vacía y el desplegable
 * de localidad quedaba **siempre** vacío, sin error y sin nada que mirar —el
 * `geoError` no se enciende porque la request salió 200. Eso hacía imposible
 * elegir localidad y, como el punto del mapa alcanza para enviar, la denuncia se
 * mandaba sin `localityId`; el PDF público, que sólo puede mostrar
 * provincia/partido/localidad, salía sin ninguna ubicación.
 *
 * El test del ViewModel no lo agarra porque construye el DTO en Kotlin y nunca
 * pasa por el JSON. Por eso el caso vive en `GeoDtoTest`, sobre el payload real.
 */
@Serializable
data class LocalityListResponseDto(
    val localities: List<LocalityDto> = emptyList(),
    val total: Int = 0,
    val adminLevel2Id: Int? = null,
)

@Serializable
data class CountryDto(
    val id: Int,
    val name: String,
    val nameLocal: String? = null,
    val isoCode2: String? = null,
    val isoCode3: String? = null,
)

@Serializable
data class AdminLevel1Dto(
    val id: Int,
    val countryId: Int? = null,
    val name: String,
    val isoCode: String? = null,
    /** "Provincia", "Estado", etc. Varía por país. */
    val type: String? = null,
    val displayOrder: Int? = null,
)

@Serializable
data class AdminLevel2Dto(
    val id: Int,
    val adminLevel1Id: Int? = null,
    val name: String,
    /** "Departamento", "Partido", … */
    val type: String? = null,
)

@Serializable
data class LocalityDto(
    val id: Int,
    val adminLevel2Id: Int? = null,
    val name: String,
    val type: String? = null,
    val postalCode: String? = null,
    /**
     * La localidad trae coordenadas: se puede centrar el mapa al elegirla, sin
     * pedirle geocoding a nadie. El front web no lo aprovecha.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Resultado de la búsqueda por texto (`SearchResultsResponse`).
 *
 * Existe en el backend y el front web no la usa: carga la jerarquía con cuatro
 * selects encadenados. En un teléfono, escribir "Villa Crespo" y elegir de una
 * lista es bastante mejor que cuatro desplegables.
 */
@Serializable
data class LocalitySearchResponseDto(
    val results: List<LocalityFullDto> = emptyList(),
    val total: Int = 0,
    val query: String? = null,
)

/** Localidad con la jerarquía completa desnormalizada: no hace falta encadenar nada. */
@Serializable
data class LocalityFullDto(
    val id: Int,
    val name: String,
    val type: String? = null,
    val postalCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** "Villa Crespo, CABA, Argentina" — listo para mostrar. */
    val fullName: String? = null,
    val shortName: String? = null,
    val adminLevel2: AdminLevel2InfoDto? = null,
    val adminLevel1: AdminLevel1InfoDto? = null,
    val country: CountryInfoDto? = null,
) {
    @Serializable
    data class AdminLevel2InfoDto(val id: Int, val name: String, val type: String? = null)

    @Serializable
    data class AdminLevel1InfoDto(
        val id: Int,
        val name: String,
        val type: String? = null,
        val isoCode: String? = null,
    )

    @Serializable
    data class CountryInfoDto(val id: Int, val name: String, val isoCode: String? = null)
}

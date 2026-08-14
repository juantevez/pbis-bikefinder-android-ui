package pbis.bike.finder.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Geocoding inverso de OpenStreetMap: de un punto a una dirección.
 *
 * Es el **único** servicio externo que consume la app, y no pasa por el gateway.
 * Nominatim es gratuito pero tiene política de uso: máximo 1 request por
 * segundo y un `User-Agent` que identifique a la aplicación. Quien abusa se come
 * un bloqueo por IP.
 *
 * El front web lo llama en cada click y en cada `dragend` del marcador, sin
 * debounce y sin `User-Agent` propio. Acá se llama **sólo cuando el usuario
 * pide la dirección**, con un botón: una request por toque deliberado, no una
 * ráfaga por cada duda al arrastrar el marcador.
 */
interface NominatimApi {

    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "jsonv2",
        /** 18 ≈ nivel de calle y altura. */
        @Query("zoom") zoom: Int = 18,
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("accept-language") language: String = "es",
    ): NominatimReverseDto
}

@Serializable
data class NominatimReverseDto(
    @SerialName("display_name") val displayName: String? = null,
    val address: NominatimAddressDto? = null,
)

/**
 * La dirección estructurada de OSM.
 *
 * Los campos de ciudad son tres alternativas del mismo dato —`city`, `town`,
 * `village`— según el tamaño del lugar, y nunca vienen los tres. No se usan para
 * completar el formulario: la localidad de la denuncia es la del backend, con su
 * id, y una cadena de OSM no se puede mapear a eso sin adivinar.
 */
@Serializable
data class NominatimAddressDto(
    val road: String? = null,
    @SerialName("house_number") val houseNumber: String? = null,
    val neighbourhood: String? = null,
    val suburb: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val state: String? = null,
    val country: String? = null,
)

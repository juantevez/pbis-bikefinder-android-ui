package pbis.bike.finder.data.remote.api

import pbis.bike.finder.data.remote.HEADER_SKIP_AUTH
import pbis.bike.finder.data.remote.dto.AdminLevel1ListResponseDto
import pbis.bike.finder.data.remote.dto.AdminLevel2ListResponseDto
import pbis.bike.finder.data.remote.dto.CountryListResponseDto
import pbis.bike.finder.data.remote.dto.CreatePaymentRequestDto
import pbis.bike.finder.data.remote.dto.LocalityListResponseDto
import pbis.bike.finder.data.remote.dto.LocalitySearchResponseDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesRequestDto
import pbis.bike.finder.data.remote.dto.PaymentResponseDto
import pbis.bike.finder.data.remote.dto.ResumenUsuarioDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * location-service. Sin autenticación.
 *
 * Cuatro requests encadenadas para llenar un domicilio. Existe además una
 * búsqueda por texto que el front web no usa y que en un teléfono es bastante
 * mejor UX que cuatro desplegables.
 */
interface GeoApi {

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/countries")
    suspend fun countries(): CountryListResponseDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/countries/{countryId}/level1")
    suspend fun provinces(@Path("countryId") countryId: Int): AdminLevel1ListResponseDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/level1/{provinceId}/level2")
    suspend fun departments(@Path("provinceId") provinceId: Int): AdminLevel2ListResponseDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/level2/{departmentId}/localities")
    suspend fun localities(@Path("departmentId") departmentId: Int): LocalityListResponseDto

    /**
     * Búsqueda por texto. Cada resultado trae provincia y partido, así que
     * resuelve la jerarquía entera en una sola request.
     */
    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/localities/search")
    suspend fun searchLocalities(
        @Query("q") query: String,
        @Query("countryId") countryId: Int? = null,
        @Query("limit") limit: Int = 20,
    ): LocalitySearchResponseDto
}

/** payment-service. */
interface PaymentApi {

    /**
     * La `X-Idempotency-Key` va explícita como parámetro y no la pone un
     * interceptor a propósito: tiene que ser **la misma** en todos los reintentos
     * de un mismo intento de pago, y eso lo sabe quien orquesta el pago, no la
     * capa de red. Un interceptor que genere una clave por request es
     * exactamente el bug que la idempotencia existe para evitar.
     *
     * La respuesta puede venir en `PROCESSING`, que **no es terminal**: no
     * alcanza para dar por pagado el plan.
     */
    @POST("api/v1/payments")
    suspend fun createPayment(
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Body body: CreatePaymentRequestDto,
    ): PaymentResponseDto
}

/** notification-service. */
interface NotificationApi {

    @GET("api/v1/notification-preferences")
    suspend fun preferences(): NotificationPreferencesDto

    /**
     * **Reemplaza el estado completo**, no parchea: hay que mandar todos los
     * campos o se apagan canales sin querer.
     *
     * El email no viaja: el backend lo toma de `X-User-Email`, que inyecta el
     * gateway desde el token.
     */
    @PUT("api/v1/notification-preferences")
    suspend fun updatePreferences(
        @Body body: NotificationPreferencesRequestDto,
    ): NotificationPreferencesDto
}

/** dashboard-aggregator. Contrato en español y plano, propio de este servicio. */
interface DashboardApi {

    @GET("api/dashboard/usuario/resumen")
    suspend fun userSummary(): ResumenUsuarioDto
}

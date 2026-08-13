package pbis.bike.finder.data.remote.api

import okhttp3.MultipartBody
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleListResponseDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDetailsDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDto
import pbis.bike.finder.data.remote.dto.FrameSizeDto
import pbis.bike.finder.data.remote.dto.InitialFormDataDto
import pbis.bike.finder.data.remote.dto.PhotoListResponseDto
import pbis.bike.finder.data.remote.dto.PhotoUploadResponseDto
import pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto
import pbis.bike.finder.data.remote.dto.RegisterManuallyRequestDto
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.TheftReportDto
import pbis.bike.finder.data.remote.dto.UpdateComponentsRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** bike-registration y media-service. */
interface BicycleApi {

    /** Ojo: devuelve `{ bicycles, total }`, y sus items son resúmenes planos. */
    @GET("api/v1/bicycles")
    suspend fun list(): BicycleListResponseDto

    /** El detalle anida marca y modelo en `frame`, distinto del resumen. */
    @GET("api/v1/bicycles/{id}")
    suspend fun detail(@Path("id") id: String): BicycleDto

    /** Baja por venta. */
    @DELETE("api/v1/bicycles/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @POST("api/v1/bicycles/from-catalog")
    suspend fun registerFromCatalog(@Body body: RegisterFromCatalogRequestDto): BicycleDto

    @POST("api/v1/bicycles/manual")
    suspend fun registerManually(@Body body: RegisterManuallyRequestDto): BicycleDto

    /**
     * El cuerpo lleva la metadata de procedencia ya calculada por el cliente.
     * Ver [UpdateComponentsRequestDto].
     */
    @PATCH("api/v1/bicycles/{id}/components")
    suspend fun updateComponents(
        @Path("id") id: String,
        @Body body: UpdateComponentsRequestDto,
    ): Response<Unit>

    @GET("api/v1/bicycles/{id}/photos")
    suspend fun photos(@Path("id") id: String): PhotoListResponseDto

    /**
     * Multipart. El `Content-Type` con boundary lo pone OkHttp: declararlo a mano
     * rompe el parseo del lado del servidor.
     *
     * Las fotos se suben **después** de crear la bici, y una falla acá no
     * invalida el alta: el front web sube de a 3 en paralelo y sólo cuenta
     * cuántas fallaron.
     */
    @Multipart
    @POST("api/v1/bicycles/{id}/photos")
    suspend fun uploadPhoto(
        @Path("id") id: String,
        @Part file: MultipartBody.Part,
        @Part("photoType") photoType: okhttp3.RequestBody,
        @Part("setAsPrimary") setAsPrimary: okhttp3.RequestBody,
        @Part("gpsAnalysisConsent") gpsAnalysisConsent: okhttp3.RequestBody,
    ): PhotoUploadResponseDto

    @POST("api/v1/bicycles/{id}/report-theft")
    suspend fun reportTheft(
        @Path("id") id: String,
        @Body body: ReportTheftRequestDto,
    ): TheftReportDto

    // ── Catálogo ─────────────────────────────────────────────────────────────
    // Todo esto es de referencia y no cambia entre sesiones: es lo primero que
    // conviene cachear en Room.

    @GET("api/v1/catalog/form-data")
    suspend fun catalogFormData(): InitialFormDataDto

    @GET("api/v1/catalog/brands/{brandId}/bikes")
    suspend fun catalogBikesByBrand(
        @Path("brandId") brandId: Long,
        @Query("bikeTypeId") bikeTypeId: Long? = null,
    ): List<CatalogBikeDto>

    @GET("api/v1/catalog/bikes/{catalogBikeId}")
    suspend fun catalogBikeDetails(@Path("catalogBikeId") catalogBikeId: Long): CatalogBikeDetailsDto

    /** El `sizeSystemId` sale del `BikeTypeDto` elegido. */
    @GET("api/v1/catalog/size-systems/{sizeSystemId}/sizes")
    suspend fun catalogSizes(@Path("sizeSystemId") sizeSystemId: Long): List<FrameSizeDto>
}

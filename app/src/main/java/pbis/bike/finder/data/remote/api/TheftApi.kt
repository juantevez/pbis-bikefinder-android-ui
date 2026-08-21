package pbis.bike.finder.data.remote.api

import pbis.bike.finder.data.remote.HEADER_SKIP_AUTH
import pbis.bike.finder.data.remote.dto.ConversationDto
import pbis.bike.finder.data.remote.dto.MessageSentDto
import pbis.bike.finder.data.remote.dto.PdfGeneratedDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.remote.dto.SubmitTipRequestDto
import pbis.bike.finder.data.remote.dto.TheftReportListResponseDto
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipFormInfoDto
import pbis.bike.finder.data.remote.dto.TipListResponseDto
import pbis.bike.finder.data.remote.dto.TipStatsDto
import pbis.bike.finder.data.remote.dto.TipSubmittedDto
import pbis.bike.finder.data.remote.dto.UnreadTipsCountDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/** theft-report: denuncias del dueño. */
interface TheftReportApi {

    @GET("api/v1/my-theft-reports")
    suspend fun myReports(): TheftReportListResponseDto

    /**
     * Una sola request para todos los badges. Reemplazó un N+1 (stats por cada
     * reporte) con un GROUP BY, y devuelve **sólo** las denuncias con pistas
     * nuevas: las ausentes valen cero.
     *
     * Es el candidato natural a que lo reemplace un push de FCM.
     */
    @GET("api/v1/my-theft-reports/tips/unread-count")
    suspend fun unreadTipsCount(): UnreadTipsCountDto

    /** Devuelve una URL prefirmada de S3: el PDF no se arma en el cliente. */
    @GET("api/v1/theft-reports/{reportId}/pdf/generate")
    suspend fun generatePdf(@Path("reportId") reportId: String): PdfGeneratedDto

    /**
     * El cartel de "¿viste esta bicicleta?", con el QR para reportar pistas.
     *
     * Va **sin `Authorization` a propósito**: es la vista que se comparte, y el
     * endpoint es público del lado del servidor. Mandar el token no lo rompería,
     * pero dejaría creer que hace falta una sesión para algo cuyo sentido es
     * pasar de mano en mano.
     *
     * Es también el PDF que sólo puede mostrar provincia, partido y localidad
     * —la calle la omite por ser dato sensible—, así que es el que se queda sin
     * ubicación si la denuncia viajó sin `localityId`.
     */
    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/stolen-bikes/{reportId}/pdf/generate")
    suspend fun generatePublicPdf(@Path("reportId") reportId: String): PdfGeneratedDto

    // ── Pistas, lado dueño ───────────────────────────────────────────────────

    @GET("api/v1/theft-reports/{reportId}/tips")
    suspend fun tips(@Path("reportId") reportId: String): TipListResponseDto

    @GET("api/v1/theft-reports/{reportId}/tips/stats")
    suspend fun tipStats(@Path("reportId") reportId: String): TipStatsDto

    /** `informantContact` sólo viene si la denuncia ofrece recompensa. */
    @GET("api/v1/theft-reports/{reportId}/tips/{tipId}")
    suspend fun tip(
        @Path("reportId") reportId: String,
        @Path("tipId") tipId: String,
    ): TipDto

    @GET("api/v1/theft-reports/{reportId}/tips/{tipId}/messages")
    suspend fun tipConversation(
        @Path("reportId") reportId: String,
        @Path("tipId") tipId: String,
    ): ConversationDto

    @POST("api/v1/theft-reports/{reportId}/tips/{tipId}/messages")
    suspend fun replyToTip(
        @Path("reportId") reportId: String,
        @Path("tipId") tipId: String,
        @Body body: SendMessageRequestDto,
    ): MessageSentDto

    @POST("api/v1/theft-reports/{reportId}/tips/{tipId}/mark-read")
    suspend fun markTipRead(
        @Path("reportId") reportId: String,
        @Path("tipId") tipId: String,
    ): Response<Unit>

    /** Irreversible: la pista pasa a ser un avistamiento oficial de la denuncia. */
    @POST("api/v1/theft-reports/{reportId}/tips/{tipId}/convert-to-sighting")
    suspend fun convertTipToSighting(
        @Path("reportId") reportId: String,
        @Path("tipId") tipId: String,
    ): Response<Unit>
}

/**
 * Lado informante: **sin login**, autenticado por un token en la URL.
 *
 * Estas rutas son App Links naturales: se llega por un link que alguien
 * compartió, no navegando dentro de la app. El gateway las tiene como públicas,
 * y por eso mismo borra los headers `X-User-*` que mande el cliente.
 */
interface PublicTipApi {

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/tips/{token}/info")
    suspend fun tipFormInfo(@Path("token") token: String): TipFormInfoDto

    /**
     * Responde **429** con `message` propio cuando hay rate limit: ahí no
     * conviene rehabilitar el botón de enviar.
     *
     * La respuesta trae `conversationToken`, que es el link del informante para
     * seguir el hilo. El front web lo descarta; acá hay que guardarlo.
     */
    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("api/v1/tips/{token}")
    suspend fun submitTip(
        @Path("token") token: String,
        @Body body: SubmitTipRequestDto,
    ): TipSubmittedDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @GET("api/v1/conversations/{token}")
    suspend fun conversation(@Path("token") token: String): ConversationDto

    @Headers("$HEADER_SKIP_AUTH: true")
    @POST("api/v1/conversations/{token}")
    suspend fun sendMessage(
        @Path("token") token: String,
        @Body body: SendMessageRequestDto,
    ): MessageSentDto
}

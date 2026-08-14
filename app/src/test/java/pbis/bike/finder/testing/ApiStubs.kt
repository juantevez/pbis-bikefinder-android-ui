package pbis.bike.finder.testing

import okhttp3.MultipartBody
import okhttp3.RequestBody
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.api.TheftReportApi
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleListResponseDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDetailsDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDto
import pbis.bike.finder.data.remote.dto.ConversationDto
import pbis.bike.finder.data.remote.dto.FrameSizeDto
import pbis.bike.finder.data.remote.dto.InitialFormDataDto
import pbis.bike.finder.data.remote.dto.MessageSentDto
import pbis.bike.finder.data.remote.dto.PdfGeneratedDto
import pbis.bike.finder.data.remote.dto.PhotoListResponseDto
import pbis.bike.finder.data.remote.dto.PhotoUploadResponseDto
import pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto
import pbis.bike.finder.data.remote.dto.RegisterManuallyRequestDto
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.remote.dto.TheftReportDto
import pbis.bike.finder.data.remote.dto.TheftReportListResponseDto
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipListResponseDto
import pbis.bike.finder.data.remote.dto.TipStatsDto
import pbis.bike.finder.data.remote.dto.UnreadTipsCountDto
import pbis.bike.finder.data.remote.dto.UpdateComponentsRequestDto
import retrofit2.Response

/**
 * Implementaciones vacías de las interfaces grandes de Retrofit.
 *
 * Cada test sobreescribe los dos o tres métodos que le importan. Lo demás
 * explota a propósito: si un test toca un endpoint que no declaró, es más útil
 * que falle a que reciba un valor por defecto inventado y pase por la razón
 * equivocada.
 */
private fun notUsed(): Nothing =
    throw UnsupportedOperationException("Este endpoint no participa del test")

abstract class StubBicycleApi : BicycleApi {
    override suspend fun list(): BicycleListResponseDto = notUsed()
    override suspend fun detail(id: String): BicycleDto = notUsed()
    override suspend fun delete(id: String): Response<Unit> = notUsed()
    override suspend fun registerFromCatalog(body: RegisterFromCatalogRequestDto): BicycleDto =
        notUsed()

    override suspend fun registerManually(body: RegisterManuallyRequestDto): BicycleDto = notUsed()
    override suspend fun updateComponents(
        id: String,
        body: UpdateComponentsRequestDto,
    ): Response<Unit> = notUsed()

    override suspend fun photos(id: String): PhotoListResponseDto = notUsed()
    override suspend fun uploadPhoto(
        id: String,
        file: MultipartBody.Part,
        photoType: RequestBody,
        setAsPrimary: RequestBody,
        gpsAnalysisConsent: RequestBody,
    ): PhotoUploadResponseDto = notUsed()

    override suspend fun reportTheft(id: String, body: ReportTheftRequestDto): TheftReportDto =
        notUsed()

    override suspend fun catalogFormData(): InitialFormDataDto = notUsed()
    override suspend fun catalogBikesByBrand(
        brandId: Long,
        bikeTypeId: Long?,
    ): List<CatalogBikeDto> = notUsed()

    override suspend fun catalogBikeDetails(catalogBikeId: Long): CatalogBikeDetailsDto = notUsed()
    override suspend fun catalogSizes(sizeSystemId: Long): List<FrameSizeDto> = notUsed()
}

abstract class StubTheftReportApi : TheftReportApi {
    override suspend fun myReports(): TheftReportListResponseDto = notUsed()
    override suspend fun unreadTipsCount(): UnreadTipsCountDto = notUsed()
    override suspend fun generatePdf(reportId: String): PdfGeneratedDto = notUsed()
    override suspend fun generatePublicPdf(reportId: String): PdfGeneratedDto = notUsed()
    override suspend fun tips(reportId: String): TipListResponseDto = notUsed()
    override suspend fun tipStats(reportId: String): TipStatsDto = notUsed()
    override suspend fun tip(reportId: String, tipId: String): TipDto = notUsed()
    override suspend fun tipConversation(reportId: String, tipId: String): ConversationDto =
        notUsed()

    override suspend fun replyToTip(
        reportId: String,
        tipId: String,
        body: SendMessageRequestDto,
    ): MessageSentDto = notUsed()

    override suspend fun markTipRead(reportId: String, tipId: String): Response<Unit> = notUsed()
    override suspend fun convertTipToSighting(
        reportId: String,
        tipId: String,
    ): Response<Unit> = notUsed()
}

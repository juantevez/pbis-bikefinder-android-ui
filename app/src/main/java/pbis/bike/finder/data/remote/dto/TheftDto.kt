package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

// ─────────────────────────────────────────────────────────────────────────────
// theft-report — com.bikefinder.theft.application.dto.TheftDto
//                com.bikefinder.theft.infrastructure.adapter.in.rest.dto.ReportPdfDto
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
enum class ReportStatus { ACTIVE, FOUND, CLOSED }

/**
 * Ubicación de un robo o de un avistamiento. La comparten la denuncia y la
 * pista (`TheftLocationRequest` / `TheftLocationResponse`).
 *
 * Los límites de longitud salen de las constraints del backend y se replican
 * acá para validar antes del viaje. El rango de lat/lng no sale de la columna
 * sino de la Tierra: sin eso "entra cualquier double y después aparece una
 * denuncia en medio del Atlántico" (comentario del propio DTO).
 */
@Serializable
data class TheftLocationDto(
    val localityId: Int? = null,
    /** max 20 */
    val streetType: String? = null,
    /** max 200 */
    val streetName: String? = null,
    /** max 20 */
    val streetNumber: String? = null,
    /** max 200 */
    val intersection: String? = null,
    /** max 500 */
    val reference: String? = null,
    /** -90.0 .. 90.0 */
    val latitude: Double? = null,
    /** -180.0 .. 180.0 */
    val longitude: Double? = null,
    /** max 20. "EXACT" en las pistas. */
    val precision: String? = null,
    /**
     * Sólo en respuesta: la dirección ya formateada por el backend. El front web
     * la ignora y rearma el texto a mano juntando calle, altura y localidad.
     */
    val formattedAddress: String? = null,
) {
    companion object {
        const val MAX_STREET_TYPE = 20
        const val MAX_STREET_NAME = 200
        const val MAX_STREET_NUMBER = 20
        const val MAX_INTERSECTION = 200
        const val MAX_REFERENCE = 500
        val LATITUDE_RANGE = -90.0..90.0
        val LONGITUDE_RANGE = -180.0..180.0
    }
}

/** `POST /api/v1/bicycles/{bicycleId}/report-theft`. */
@Serializable
data class ReportTheftRequestDto(
    val theftDate: LocalDate,
    /** max 50 */
    val theftTimeApprox: String? = null,
    val theftLocation: TheftLocationDto? = null,
    /**
     * max 5000. La columna es TEXT (sin techo); el límite es decisión de
     * producto y es el único que existe, así que conviene respetarlo en la UI.
     */
    val theftDescription: String? = null,
    /** max 50 */
    val contactPhone: String? = null,
    /** `@Email`, max 255 */
    val contactEmail: String? = null,
    val contactPublic: Boolean = false,
    val rewardOffered: Boolean = false,
    /** `BigDecimal` >= 0, 10 enteros + 2 decimales. Ver [toBigDecimalAmount]. */
    val rewardAmount: String? = null,
    /** ISO 4217: `^[A-Z]{3}$` */
    val rewardCurrency: String? = null,
) {
    companion object {
        const val MAX_TIME_APPROX = 50
        const val MAX_DESCRIPTION = 5000
        const val MAX_CONTACT_PHONE = 50
        const val MAX_CONTACT_EMAIL = 255
        val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
    }
}

/**
 * Ediciones de una denuncia ya creada. **El front web no las implementa**: una
 * vez presentada, la denuncia no se puede corregir desde la UI. El backend sí
 * las soporta.
 */
@Serializable
data class UpdateTheftDetailsRequestDto(
    val theftDate: LocalDate? = null,
    val theftTimeApprox: String? = null,
    val theftLocation: TheftLocationDto? = null,
    val theftDescription: String? = null,
)

@Serializable
data class UpdateContactRequestDto(
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val contactPublic: Boolean = false,
)

@Serializable
data class UpdateRewardRequestDto(
    val rewardOffered: Boolean = false,
    val rewardAmount: String? = null,
    val rewardCurrency: String? = null,
)

// ── Respuestas ───────────────────────────────────────────────────────────────

@Serializable
data class TheftReportListResponseDto(
    val reports: List<TheftReportDto> = emptyList(),
    val total: Int = 0,
)

/**
 * `TheftReportResponse`.
 *
 * El front web lee sólo `id`, `status`, `theftDate` y `bicycleId`: ignora
 * `foundAt`, `closedAt` y `sightingsCount`, y por eso no distingue en pantalla
 * una denuncia recuperada (FOUND) de una cerrada (CLOSED).
 */
@Serializable
data class TheftReportDto(
    val id: String,
    val bicycleId: String? = null,
    val reportedBy: String? = null,
    val status: ReportStatus? = null,
    val theftDate: LocalDate? = null,
    val theftTimeApprox: String? = null,
    val theftLocation: TheftLocationDto? = null,
    val theftDescription: String? = null,
    val contact: ContactDto? = null,
    val reward: RewardDto? = null,
    val reportedAt: Instant? = null,
    val updatedAt: Instant? = null,
    val foundAt: Instant? = null,
    val closedAt: Instant? = null,
    val sightingsCount: Int = 0,
)

@Serializable
data class ContactDto(
    val phone: String? = null,
    val email: String? = null,
    val isPublic: Boolean = false,
)

@Serializable
data class RewardDto(
    val offered: Boolean = false,
    /** Mismo caso que el pago: viaja como número JSON. */
    @Serializable(with = LenientAmountSerializer::class)
    val amount: String? = null,
    val currency: String? = null,
    /** Ya formateado por el backend, listo para mostrar. */
    val formatted: String? = null,
)

/** Conteo de pistas sin leer: `GET /api/v1/my-theft-reports/tips/unread-count`. */
@Serializable
data class UnreadTipsCountDto(
    val total: Int = 0,
    /**
     * Sólo trae las denuncias **con** pistas nuevas; las ausentes valen cero.
     * Reemplazó un N+1 (una request de stats por reporte) con un GROUP BY.
     *
     * Es el candidato natural a que lo sustituya un push de FCM: hoy hay que
     * preguntar para enterarse.
     */
    val porReporte: Map<String, Int> = emptyMap(),
)

// ── PDF ──────────────────────────────────────────────────────────────────────

/**
 * `GET /api/v1/theft-reports/{id}/pdf/generate`.
 *
 * El PDF **no** se arma en el cliente: el backend lo genera y devuelve una URL
 * prefirmada de S3. En Android eso es un `DownloadManager` o un
 * `Intent.ACTION_VIEW` — más simple que en web.
 */
@Serializable
data class PdfGeneratedDto(
    val presignedUrl: String,
    val version: Int = 0,
    val wasRegenerated: Boolean = false,
    val fileSizeBytes: Long = 0,
)

/**
 * Historial de versiones del PDF. El front web no lo expone.
 *
 * `isStale` permitiría avisarle al dueño que el PDF que descargó quedó viejo
 * después de editar la denuncia.
 */
@Serializable
data class PdfVersionDto(
    val id: String,
    val pdfType: String? = null,
    val version: Int = 0,
    val isActive: Boolean = false,
    val isStale: Boolean = false,
    val fileSizeBytes: Long = 0,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val generatedAt: kotlinx.datetime.LocalDateTime? = null,
    /** null si no expira. */
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val expiresAt: kotlinx.datetime.LocalDateTime? = null,
)

// ── Formulario público de pistas ─────────────────────────────────────────────

/**
 * `GET /api/v1/tips/{token}/info` — la bicicleta que ve quien escaneó el cartel.
 *
 * Es el mismo `StolenBikeResponse` que devuelve `GET /api/v1/stolen-bikes/{id}`,
 * y eso es deliberado: el criterio de privacidad no tiene por qué cambiar por la
 * puerta de entrada. Hasta la localidad, **sin calle ni altura**, y con las
 * coordenadas redondeadas a ~1 km — el mapa ubica la zona sin señalar la puerta
 * de la víctima.
 *
 * Todo es nullable salvo `reportId`: una denuncia puede no tener recompensa, ni
 * contacto público, ni haber cargado el año de la bici.
 */
@Serializable
data class TipFormInfoDto(
    val reportId: String,
    val bicycleId: String? = null,
    val theftDate: LocalDate? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val reportedAt: kotlinx.datetime.LocalDateTime? = null,
    val location: TipFormLocationDto? = null,
    val bike: TipFormBikeDto? = null,
    val reward: TipFormRewardDto? = null,
)

@Serializable
data class TipFormLocationDto(
    val localityName: String? = null,
    val provinceName: String? = null,
    val departmentName: String? = null,
    /** Redondeadas a ~1 km del lado del backend; no son la dirección del robo. */
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class TipFormBikeDto(
    /** Id del tipo de bici, no su nombre. */
    val type: Int? = null,
    val primaryColorId: Int? = null,
    val secondaryColorId: Int? = null,
    val brandName: String? = null,
    val modelName: String? = null,
    val year: Int? = null,
)

@Serializable
data class TipFormRewardDto(
    val offered: Boolean = false,
    /** Ya formateada por el backend: "ARS 50000". */
    val formatted: String? = null,
)

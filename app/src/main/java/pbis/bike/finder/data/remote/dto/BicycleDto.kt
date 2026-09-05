package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.Instant

// ─────────────────────────────────────────────────────────────────────────────
// bike-registration — com.bikefinder.registration.application.dto.BicycleDto
// media-service     — com.bikefinder.media.application.dto.PhotoDto
// ─────────────────────────────────────────────────────────────────────────────

// ── Enums del dominio ────────────────────────────────────────────────────────

/** Sólo `ACTIVE` puede editarse, venderse o denunciarse (`BicycleStatus`). */
@Serializable
enum class BicycleStatus { ACTIVE, STOLEN, SOLD, INACTIVE }

@Serializable
enum class RegistrationType { CATALOG, MANUAL }

/**
 * `PurchaseMethod`. El backend le asocia un nombre para mostrar en español;
 * acá se repite porque el DTO expone el enum crudo, no el `displayName`.
 */
@Serializable
enum class PurchaseMethod(val displayName: String) {
    PHYSICAL_STORE_NEW("Tienda física, bici nueva"),
    ONLINE_BRAND_OFFICIAL("Sitio oficial de la marca"),
    ONLINE_MARKETPLACE_RETAILER("Retailer - Vendedor profesional"),
    ONLINE_MARKETPLACE_PRIVATE("Online - Particular"),
    SECOND_HAND_PRIVATE("Venta directa entre particulares - usada"),
    GIFT("Regalo"),
    CORPORATE_LEASING("Leasing corporativo"),
    OTHER("Otro"),
}

@Serializable
enum class PhotoType(val displayName: String) {
    GENERAL("Vista general"),
    FRONT("Frente"),
    SIDE_LEFT("Lateral izquierdo"),
    SIDE_RIGHT("Lateral derecho"),
    SERIAL_NUMBER("Número de serie"),
    DETAIL("Detalle"),
    DAMAGE("Daño/marca"),
    RECEIPT("Comprobante de compra"),
}

// ── Requests de alta ─────────────────────────────────────────────────────────

@Serializable
data class RegisterFromCatalogRequestDto(
    val catalogBikeId: Long,
    val colorwayId: Long? = null,
    val frameSize: String? = null,
    val serialNumber: String? = null,
    /** Sin esquema en el backend (`Map<String, Object>`). El front web no lo usa. */
    val componentOverrides: JsonObject? = null,
    val purchaseDate: LocalDate? = null,
    /** `BigDecimal` en el backend: se manda como texto. Ver [toBigDecimalAmount]. */
    val purchasePrice: String? = null,
    val purchaseMethod: PurchaseMethod? = null,
    val purchaseReceiptUrl: String? = null,
    val notes: String? = null,
)

@Serializable
data class RegisterManuallyRequestDto(
    val brandId: Long,
    val model: String? = null,
    val year: Int? = null,
    val frameSize: String? = null,
    val serialNumber: String? = null,
    val bikeTypeId: Long? = null,
    /** Se manda `primaryColorId` **o** [primaryColorCustom]; el front exige uno de los dos. */
    val primaryColorId: Long? = null,
    val primaryColorCustom: String? = null,
    val secondaryColorId: Long? = null,
    val accentColorId: Long? = null,
    val colorDescription: String? = null,
    val components: JsonObject? = null,
    val detailedSpecs: JsonObject? = null,
    val purchaseDate: LocalDate? = null,
    val purchasePrice: String? = null,
    val purchaseMethod: PurchaseMethod? = null,
    val purchaseReceiptUrl: String? = null,
    val notes: String? = null,
)

/**
 * `PATCH /api/v1/bicycles/{id}/components`.
 *
 * El mapa no tiene esquema y el cliente manda la metadata de procedencia ya
 * calculada: `isOriginal`, `source`, `updatedAt`, `originalBrand`,
 * `originalModel`. Esa lógica de diff vive hoy en el front web
 * (`actualizar-componentes.js:120-175`) y hay que portarla tal cual o el
 * historial de qué pieza es de fábrica y cuál se cambió queda corrupto.
 *
 * El backend además **devuelve** `originalComponents` en la respuesta, así que
 * tiene con qué calcularlo él: es el mejor candidato a mover al servidor.
 */
@Serializable
data class UpdateComponentsRequestDto(val components: JsonObject)

// ── Respuestas ───────────────────────────────────────────────────────────────

/**
 * `GET /api/v1/bicycles` — lista.
 *
 * Ojo: **no** es una lista de [BicycleDto]. El resumen es plano (marca y modelo
 * al tope) mientras que el detalle los anida en `frame`. Son dos modelos.
 */
@Serializable
data class BicycleListResponseDto(
    val bicycles: List<BicycleSummaryDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class BicycleSummaryDto(
    val id: String,
    val brandName: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val serialNumber: String? = null,
    val primaryColor: String? = null,
    val status: BicycleStatus? = null,
    val updatedAt: Instant? = null,
)

/** `GET /api/v1/bicycles/{id}` — detalle (`BicycleResponse`). */
@Serializable
data class BicycleDto(
    val id: String,
    val ownerId: String? = null,
    val registrationType: RegistrationType? = null,
    val catalogBikeId: Long? = null,
    val selectedColorwayId: Long? = null,
    val frame: FrameInfoDto? = null,
    val bikeTypeId: Long? = null,
    val colors: ColorsDto? = null,
    val components: JsonObject? = null,
    /** Estado de fábrica, para comparar contra [components]. */
    val originalComponents: JsonObject? = null,
    val detailedSpecs: JsonObject? = null,
    /**
     * Marcas distintivas de la bici. El backend las modela y **no hay UI que las
     * cargue ni las muestre** en el front web: en una app de identificación de
     * bicis robadas, es funcionalidad de producto sin usar.
     */
    val distinguishingMarks: List<JsonObject> = emptyList(),
    /** Viene en el detalle, pero el front igual repide `/photos`. */
    val photos: List<JsonObject> = emptyList(),
    val purchaseInfo: PurchaseInfoDto? = null,
    val notes: String? = null,
    val status: BicycleStatus? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/**
 * `FrameInfoResponse`.
 *
 * **No tiene `bikeTypeName`**: el front web lee `frame.bikeTypeName` y por eso
 * el tipo de bici sale siempre vacío en la pantalla de componentes. El nombre
 * hay que resolverlo desde `BicycleDto.bikeTypeId` contra el catálogo.
 */
@Serializable
data class FrameInfoDto(
    val brandId: Long? = null,
    val brandName: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val size: String? = null,
    val serialNumber: String? = null,
)

@Serializable
data class ColorsDto(
    val primaryColorId: Long? = null,
    val primaryColor: String? = null,
    val primaryColorCustom: String? = null,
    val secondaryColorId: Long? = null,
    val secondaryColor: String? = null,
    val accentColorId: Long? = null,
    val accentColor: String? = null,
    val description: String? = null,
)

@Serializable
data class PurchaseInfoDto(
    val purchaseDate: LocalDate? = null,
    val purchasePrice: String? = null,
    val currency: String? = null,
    val estimatedCurrentValue: String? = null,
    val purchaseMethod: PurchaseMethod? = null,
    val purchaseReceiptUrl: String? = null,
    val purchaseReceiptMimeType: String? = null,
)

// ── Fotos ────────────────────────────────────────────────────────────────────

@Serializable
data class PhotoListResponseDto(
    val photos: List<PhotoDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class PhotoDto(
    val id: String,
    val bicycleId: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val fileSizeBytes: Long = 0,
    val photoType: PhotoType? = null,
    val isPrimary: Boolean = false,
    val description: String? = null,
    /** Sin zona. Interpretar con [BackendTimeZone]. */
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val uploadedAt: LocalDateTime? = null,
    val downloadUrl: String? = null,
    /**
     * Clave de la miniatura, no una URL — igual que [downloadUrl].
     *
     * media-service la genera con el lado mayor en 400px y pesa cerca de veinte
     * veces menos que el original. Viene en null en las fotos anteriores a las
     * miniaturas y en las que no se pudieron procesar, y en ese caso se cae al
     * archivo grande.
     */
    val thumbnailUrl: String? = null,
    val exif: ExifDto? = null,
)

/**
 * EXIF ya parseado por media-service, **GPS incluido**.
 *
 * El front web nunca lo muestra. Es el dato que el usuario autoriza a analizar
 * con `gpsAnalysisConsent` al subir la foto: si se lo va a exponer en la app,
 * conviene que sea coherente con lo que ese consentimiento prometió.
 */
@Serializable
data class ExifDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val dateTime: LocalDateTime? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val orientation: Int? = null,
)

@Serializable
data class PhotoUploadResponseDto(
    val id: String,
    val fileName: String? = null,
    val photoType: PhotoType? = null,
    val isPrimary: Boolean = false,
    val message: String? = null,
)

/**
 * Campos del multipart de `POST /api/v1/bicycles/{id}/photos`.
 *
 * No es un `@Serializable` porque va como form-data, pero los nombres tienen
 * que coincidir exactos con los `@RequestParam` del backend.
 */
object PhotoUploadFields {
    const val FILE = "file"
    const val PHOTO_TYPE = "photoType"
    const val SET_AS_PRIMARY = "setAsPrimary"

    /**
     * Consentimiento explícito para analizar el GPS embebido en la imagen.
     * Si va en false, media-service no publica el dato hacia fraud-detection.
     */
    const val GPS_ANALYSIS_CONSENT = "gpsAnalysisConsent"
}

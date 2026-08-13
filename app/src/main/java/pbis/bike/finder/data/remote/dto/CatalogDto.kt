package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// bike-registration — com.bikefinder.registration.application.dto.CatalogDto
//
// Todo esto es de referencia y no cambia entre sesiones: es el candidato más
// claro a caché local (Room). Hoy el front lo repide en cada carga del wizard.
// ─────────────────────────────────────────────────────────────────────────────

/** `GET /api/v1/catalog/form-data` — bootstrap del wizard de alta. */
@Serializable
data class InitialFormDataDto(
    val frameBrands: List<BrandDto> = emptyList(),
    val bikeTypes: List<BikeTypeDto> = emptyList(),
    val colors: List<ColorDto> = emptyList(),
    /** El front web lo ignora: no hay UI de transmisión en el alta. */
    val speedConfigs: List<SpeedConfigDto> = emptyList(),
)

@Serializable
data class BrandDto(
    val id: Long,
    val name: String,
    val slug: String? = null,
    val country: String? = null,
    /** Sin usar en el front web; serviría para mostrar el logo en el selector. */
    val logoUrl: String? = null,
)

@Serializable
data class BikeTypeDto(
    val id: Long,
    val name: String,
    val slug: String? = null,
    val description: String? = null,
    val iconName: String? = null,
    /** Con esto se piden los talles: `/catalog/size-systems/{sizeSystemId}/sizes`. */
    val sizeSystemId: Long? = null,
)

@Serializable
data class ColorDto(
    val id: Long,
    val name: String,
    val nameEs: String? = null,
    /**
     * Permite pintar muestras de color reales en el selector, en vez de la
     * lista de texto que hay hoy en el front web.
     */
    val hexCode: String? = null,
    val colorFamily: String? = null,
)

@Serializable
data class SpeedConfigDto(
    val id: Long,
    val code: String? = null,
    val frontGears: Int = 0,
    val rearGears: Int = 0,
    val totalSpeeds: Int = 0,
)

/** `GET /api/v1/catalog/brands/{brandId}/bikes` y anidado en el detalle. */
@Serializable
data class CatalogBikeDto(
    val id: Long,
    val brandId: Long? = null,
    val brandName: String? = null,
    val modelName: String? = null,
    val modelYear: Int? = null,
    val bikeTypeId: Long? = null,
    /** Acá sí existe el nombre del tipo, a diferencia de [FrameInfoDto]. */
    val bikeTypeName: String? = null,
    val sizeSystemId: Long? = null,
    val frameMaterial: String? = null,
    val groupsetBrand: String? = null,
    val groupsetModel: String? = null,
    val speedConfig: String? = null,
    val brakeType: String? = null,
)

/** `GET /api/v1/catalog/bikes/{catalogBikeId}`. */
@Serializable
data class CatalogBikeDetailsDto(
    val bike: CatalogBikeDto? = null,
    val brand: BrandDto? = null,
    val bikeType: BikeTypeDto? = null,
    val colorways: List<ColorwayDto> = emptyList(),
    val availableSizes: List<FrameSizeDto> = emptyList(),
    val components: List<CatalogComponentDto> = emptyList(),
)

@Serializable
data class ColorwayDto(
    val id: Long,
    val catalogBikeId: Long? = null,
    val colorwayCode: String? = null,
    val colorwayName: String? = null,
    val primaryColorId: Long? = null,
    val primaryColor: String? = null,
    val secondaryColorId: Long? = null,
    val secondaryColor: String? = null,
    val accentColorId: Long? = null,
    val accentColor: String? = null,
    val finish: String? = null,
    val imageUrl: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class FrameSizeDto(
    val id: Long,
    val sizeCode: String,
    val sizeLabel: String? = null,
    val sizeCmEquivalent: Double? = null,
    /** El front arma con esto la ayuda "(165-175cm)" al lado del talle. */
    val riderHeightMinCm: Int? = null,
    val riderHeightMaxCm: Int? = null,
)

@Serializable
data class CatalogComponentDto(
    val id: Long,
    val componentTypeCode: String? = null,
    val componentTypeName: String? = null,
    val componentTypeNameEs: String? = null,
    val category: String? = null,
    val brandId: Long? = null,
    val brandName: String? = null,
    val model: String? = null,
    val specs: String? = null,
    /** true si es la pieza con la que la bici sale de fábrica. */
    val isStock: Boolean = false,
)

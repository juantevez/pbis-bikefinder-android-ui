package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// dashboard-aggregator — com.bikefinder.dashboard.domain.model.ResumenUsuario
//
// Este servicio tiene su propio contrato, en español y plano, distinto de todo
// el resto de la API. Es un agregador: no intentar unificar [BicicletaResumenDto]
// con [BicycleDto], son modelos de dos servicios diferentes que casualmente
// hablan de lo mismo.
// ─────────────────────────────────────────────────────────────────────────────

/** `GET /api/dashboard/usuario/resumen`. */
@Serializable
data class ResumenUsuarioDto(
    val totalBicicletas: Int = 0,
    val totalComponentes: Int = 0,
    val totalReportesActivos: Int = 0,
    /** Hoy siempre "Activa": el backend todavía no modela otros estados. */
    val estadoCuenta: String? = null,
    /** Alimenta los selects de los modales de venta y de robo. */
    val bicicletas: List<BicicletaResumenDto> = emptyList(),
)

@Serializable
data class BicicletaResumenDto(
    val id: String,
    val marca: String? = null,
    val modelo: String? = null,
    /**
     * El backend lo declara con tilde (`año`) y como String, no como Int.
     * `@SerialName` mantiene el nombre del JSON y deja el campo Kotlin sin tilde.
     */
    @SerialName("año") val anio: String? = null,
    /** Los mismos valores que [BicycleStatus], pero llegan como texto suelto. */
    val estado: String? = null,
    val totalComponentes: Int = 0,
)

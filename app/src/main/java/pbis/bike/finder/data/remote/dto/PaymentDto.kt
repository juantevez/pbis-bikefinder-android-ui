package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

// ─────────────────────────────────────────────────────────────────────────────
// payment-service — infrastructure.adapter.in.rest.dto.{CreatePaymentRequest,
//                   PaymentResponse}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Estado del pago (`PaymentStatus`).
 *
 * **`PROCESSING` no es terminal.** El cliente no puede asumir que la respuesta
 * del POST trae el resultado final: con un gateway de pagos de por medio, el
 * cobro puede resolverse después. El front web no maneja este estado. En la app
 * hace falta polling del pago o esperar el evento antes de dar por pagado el
 * plan y dejar seguir a la denuncia.
 */
@Serializable
enum class PaymentStatus {
    PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * `POST /api/v1/payments`.
 *
 * Va con `X-Idempotency-Key`, y **la misma clave en todos los reintentos de un
 * mismo intento de pago**: es lo único que evita cobrar dos veces. Por eso
 * [externalOrderId] se deriva de esa clave y no de un timestamp — un reintento
 * tiene que referirse a la misma operación, no inventar una orden nueva.
 */
@Serializable
data class CreatePaymentRequestDto(
    val externalOrderId: String,
    /** `BigDecimal` >= 0.01, 15 enteros + 2 decimales. Nunca `Double`: es plata. */
    val amount: String,
    /** ISO 4217. */
    val currency: String,
    val payerEmail: String,
    /** max 255 */
    val description: String,
    /**
     * Tokenizado del lado del cliente. Contra Mercado Pago real hay que generarlo
     * con su SDK: el número de tarjeta nunca viaja crudo al backend.
     *
     * En dev el servicio corre en modo stub y aprueba cualquier token salvo
     * "reject-token".
     */
    val cardToken: String,
    /** "visa" | "master" — el backend lo dice en el mensaje de validación. */
    val paymentMethodId: String,
    /**
     * 1..48. El front web manda siempre 1: las cuotas están disponibles sin
     * tocar backend.
     *
     * **`@EncodeDefault` no es decorativo.** kotlinx-serialization omite del JSON
     * los campos que valen su default, y el backend marca este con `@NotNull`:
     * sin la anotación el payload sale sin `installments` y todo pago muere con
     * `400 VALIDATION_ERROR — installments is required`. El front web no lo sufre
     * porque arma el objeto a mano y escribe `installments: 1` explícito.
     *
     * Es el único campo del contrato con un default no nulo, y por eso el único
     * que necesita esto.
     */
    @EncodeDefault
    val installments: Int = 1,
) {
    companion object {
        const val MAX_DESCRIPTION = 255
        val INSTALLMENTS_RANGE = 1..48
        const val HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key"
    }
}

@Serializable
data class PaymentResponseDto(
    val paymentId: String,
    val externalOrderId: String? = null,
    val status: PaymentStatus? = null,
    /** Llega como número JSON (`18.99`), no como cadena. Ver [LenientAmountSerializer]. */
    @Serializable(with = LenientAmountSerializer::class)
    val amount: String? = null,
    val currency: String? = null,
    val payerEmail: String? = null,
    val description: String? = null,
    /** Referencia del gateway (Mercado Pago). Útil para soporte. */
    val gatewayReference: String? = null,
    val failureReason: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/**
 * Planes de búsqueda.
 *
 * **No hay endpoint que los sirva**: están hardcodeados en el front web y se
 * replican acá. Es un segundo lugar donde cambiar un precio, y ya son dos
 * clientes que hay que redeployar para una promoción. Conviene que el backend
 * los exponga.
 */
enum class SearchPlan(
    val displayName: String,
    val priceUsd: String,
    val frequency: String,
    val months: Int,
    /** Bajada corta, bajo el nombre. */
    val tagline: String,
    /** Los tres puntos de la tarjeta, en el orden de la web. */
    val features: List<String>,
) {
    VIGIA(
        displayName = "Vigía",
        priceUsd = "9.99",
        frequency = "2× por semana",
        months = 2,
        tagline = "Búsqueda esencial",
        features = listOf(
            "Planificación de búsqueda 2 veces por semana",
            "Duración de 2 meses",
            "Reporte oficial en PDF incluido",
        ),
    ),
    SABUESO(
        displayName = "Sabueso",
        priceUsd = "18.99",
        frequency = "4× por semana",
        months = 4,
        tagline = "Búsqueda intensiva",
        features = listOf(
            "Planificación de búsqueda 4 veces por semana",
            "Duración de 4 meses",
            "Prioridad en coincidencias de imagen",
        ),
    ),
    COMANDO(
        displayName = "Comando",
        priceUsd = "26.99",
        frequency = "4× por semana",
        months = 6,
        tagline = "Búsqueda extendida",
        features = listOf(
            "Planificación de búsqueda 4 veces por semana",
            "Duración de 6 meses",
            "Máxima cobertura temporal de rastreo",
        ),
    ),
}

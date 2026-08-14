package pbis.bike.finder.data.repository

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import pbis.bike.finder.data.local.PaymentKeys
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.PaymentApi
import pbis.bike.finder.data.remote.dto.CreatePaymentRequestDto
import pbis.bike.finder.data.remote.dto.PaymentResponseDto
import pbis.bike.finder.data.remote.dto.PaymentStatus
import pbis.bike.finder.data.remote.dto.SearchPlan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cómo terminó un intento de pago.
 *
 * Los tres casos existen porque el usuario tiene que hacer algo distinto en cada
 * uno, y confundirlos cuesta plata:
 *
 *  - [Paid] — cobrado. Se puede seguir a la denuncia.
 *  - [Rejected] — la tarjeta dijo que no. Reintentar con otra **sí** tiene
 *    sentido, y la clave ya se descartó para que el backend no repita el rechazo.
 *  - [Uncertain] — no sabemos. Puede haberse cobrado. La clave se conserva, así
 *    que reintentar es seguro: el backend deduplica en vez de cobrar de nuevo.
 */
sealed interface PaymentOutcome {
    data class Paid(val payment: PaymentResponseDto) : PaymentOutcome
    data class Rejected(val reason: String?) : PaymentOutcome
    data class Uncertain(val error: ApiResult<Nothing>) : PaymentOutcome
}

@Singleton
class PaymentRepository @Inject constructor(
    private val api: PaymentApi,
    private val keyStore: PaymentKeys,
    private val json: Json,
) {
    /**
     * Cobra el plan y espera hasta saber el resultado.
     *
     * `PROCESSING` **no es terminal** y el front web no lo maneja: da el pago por
     * hecho apenas ve un 201 y manda al usuario a denunciar. Con un gateway de
     * pagos de por medio el cobro puede resolverse después, así que acá se
     * consulta hasta que el estado sea terminal o se acabe la paciencia. Un
     * `PROCESSING` que no resuelve es incierto, no un fracaso: la clave se
     * conserva.
     */
    suspend fun payForPlan(
        bicycleId: String,
        plan: SearchPlan,
        payerEmail: String,
        cardToken: String,
        paymentMethodId: String,
    ): PaymentOutcome {
        val slot = plan.name
        val idempotencyKey = keyStore.key(bicycleId, slot)

        val request = CreatePaymentRequestDto(
            // Derivado de la clave y no de un timestamp: un reintento tiene que
            // referirse a la misma operación, no inventar una orden nueva.
            externalOrderId = "theft-$bicycleId-${slot.lowercase()}-$idempotencyKey",
            amount = plan.priceUsd,
            currency = "USD",
            payerEmail = payerEmail,
            description = "Plan ${plan.displayName} — búsqueda de bicicleta robada",
            cardToken = cardToken,
            paymentMethodId = paymentMethodId,
        )

        val created = apiCall(json) { api.createPayment(idempotencyKey, request) }
        val payment = when (created) {
            is ApiResult.Success -> created.data
            is ApiResult.HttpError -> return created.toOutcome(bicycleId, slot)
            is ApiResult.NoNetwork -> return PaymentOutcome.Uncertain(ApiResult.NoNetwork)
            is ApiResult.Malformed -> return PaymentOutcome.Uncertain(created)
        }

        return settle(payment, bicycleId, slot)
    }

    /**
     * Un 422 es el único fracaso que se puede dar por definitivo.
     *
     * Cualquier otro código —503, 500, un timeout del gateway— deja el cobro en
     * duda, y ahí la clave **no** se toca.
     */
    private suspend fun ApiResult.HttpError.toOutcome(
        bicycleId: String,
        slot: String,
    ): PaymentOutcome = if (code == 422) {
        keyStore.discard(bicycleId, slot)
        PaymentOutcome.Rejected(userMessage)
    } else {
        PaymentOutcome.Uncertain(this)
    }

    /** Consulta el pago hasta que el estado deje de ser transitorio. */
    private suspend fun settle(
        initial: PaymentResponseDto,
        bicycleId: String,
        slot: String,
    ): PaymentOutcome {
        var payment = initial

        repeat(POLL_ATTEMPTS) {
            when (payment.status) {
                PaymentStatus.COMPLETED -> {
                    keyStore.discard(bicycleId, slot)
                    return PaymentOutcome.Paid(payment)
                }

                PaymentStatus.FAILED, PaymentStatus.CANCELLED -> {
                    keyStore.discard(bicycleId, slot)
                    return PaymentOutcome.Rejected(payment.failureReason)
                }

                // PENDING y PROCESSING son transitorios; null es un backend que
                // no dijo nada, que tampoco alcanza para dar por pagado.
                else -> {
                    delay(POLL_DELAY_MS)
                    payment = when (val next = apiCall(json) { api.payment(payment.paymentId) }) {
                        is ApiResult.Success -> next.data
                        // Perder la consulta no vuelve fallido el pago: puede
                        // estar cobrado. Se sale por incierto.
                        is ApiResult.HttpError -> return PaymentOutcome.Uncertain(next)
                        is ApiResult.NoNetwork -> return PaymentOutcome.Uncertain(ApiResult.NoNetwork)
                        is ApiResult.Malformed -> return PaymentOutcome.Uncertain(next)
                    }
                }
            }
        }

        // Se acabaron los intentos y sigue sin resolverse. No es un fracaso.
        return PaymentOutcome.Uncertain(ApiResult.NoNetwork)
    }

    private companion object {
        const val POLL_ATTEMPTS = 5
        const val POLL_DELAY_MS = 1_500L
    }
}

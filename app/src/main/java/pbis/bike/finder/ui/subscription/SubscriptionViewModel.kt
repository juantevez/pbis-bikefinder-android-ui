package pbis.bike.finder.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.SearchPlan
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.PaymentOutcome
import pbis.bike.finder.data.repository.PaymentRepository
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class SubscriptionUiState(
    val selectedPlan: SearchPlan? = null,
    /** El formulario de tarjeta está abierto. */
    val paying: Boolean = false,

    val cardName: String = "",
    val cardNumber: String = "",
    val cardExpiry: String = "",
    val cardCvc: String = "",

    val payerEmail: String? = null,

    val submitting: Boolean = false,
    val cardErrors: Map<String, String> = emptyMap(),
    /** Rechazo o fallo del cobro, para mostrar arriba del botón. */
    val paymentError: String? = null,
    /**
     * El cobro quedó en duda.
     *
     * Cambia el texto del botón: reintentar es seguro porque la clave de
     * idempotencia se conservó, y decirlo importa —si no, el usuario cree que
     * apretar de nuevo le cobra dos veces y abandona.
     */
    val uncertain: Boolean = false,

    /** El plan pagado. Deja de ser null una sola vez y libera la denuncia. */
    val paidPlan: SearchPlan? = null,
) {
    val canSubmit: Boolean get() = selectedPlan != null && !submitting
}

/**
 * El plan de búsqueda: el paso que va entre el dashboard y la denuncia.
 *
 * Replica `suscripcion.html`, con una diferencia de fondo: acá el pago no se da
 * por hecho con el 201. `PROCESSING` no es un estado terminal y el front web lo
 * trata como si lo fuera; ver [PaymentRepository].
 *
 * El número de tarjeta **no sale del teléfono**: se tokeniza acá, igual que en
 * la web. Contra Mercado Pago real el token lo tiene que generar su SDK; lo de
 * abajo sirve contra el modo stub del backend, que es lo único que hay en dev.
 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    private var bicycleId: String? = null

    fun start(bicycleId: String) {
        if (this.bicycleId != null) return
        this.bicycleId = bicycleId

        viewModelScope.launch {
            val result = authRepository.profile()
            if (result is ApiResult.Success) {
                _state.update { it.copy(payerEmail = result.data.email) }
            }
        }
    }

    fun selectPlan(plan: SearchPlan) = _state.update {
        it.copy(
            selectedPlan = plan,
            paying = true,
            // Cambiar de plan es otra operación —y otra clave de idempotencia—,
            // así que el error del intento anterior no aplica.
            paymentError = null,
            uncertain = false,
        )
    }

    fun dismissPayment() = _state.update { it.copy(paying = false) }

    fun setCardName(value: String) = _state.update { it.copy(cardName = value) }

    /** Reformatea a grupos de cuatro mientras se escribe, como la web. */
    fun setCardNumber(value: String) = _state.update {
        val digits = value.filter(Char::isDigit).take(16)
        it.copy(cardNumber = digits.chunked(4).joinToString(" "))
    }

    fun setCardExpiry(value: String) = _state.update {
        val digits = value.filter(Char::isDigit).take(4)
        it.copy(
            cardExpiry = if (digits.length >= 3) {
                "${digits.take(2)}/${digits.drop(2)}"
            } else {
                digits
            },
        )
    }

    fun setCardCvc(value: String) = _state.update {
        it.copy(cardCvc = value.filter(Char::isDigit).take(4))
    }

    fun pay() {
        val current = _state.value
        val plan = current.selectedPlan ?: return
        val id = bicycleId ?: return
        if (current.submitting || current.paidPlan != null) return

        val errors = validateCard(current)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(cardErrors = errors) }
            return
        }

        _state.update {
            it.copy(submitting = true, cardErrors = emptyMap(), paymentError = null)
        }

        val digits = current.cardNumber.filter(Char::isDigit)

        viewModelScope.launch {
            val outcome = paymentRepository.payForPlan(
                bicycleId = id,
                plan = plan,
                // El backend valida el formato; un pago sin email no se cae por
                // culpa de un perfil incompleto.
                payerEmail = current.payerEmail ?: FALLBACK_EMAIL,
                cardToken = cardTokenFor(digits, current.cardCvc),
                paymentMethodId = if (digits.startsWith("4")) "visa" else "master",
            )

            when (outcome) {
                is PaymentOutcome.Paid -> _state.update {
                    it.copy(submitting = false, paying = false, paidPlan = plan)
                }

                is PaymentOutcome.Rejected -> _state.update {
                    it.copy(
                        submitting = false,
                        uncertain = false,
                        paymentError = outcome.reason
                            ?: "El pago fue rechazado. Probá con otra tarjeta.",
                    )
                }

                is PaymentOutcome.Uncertain -> _state.update {
                    it.copy(
                        submitting = false,
                        uncertain = true,
                        paymentError = outcome.error.toUserMessage(
                            "No se pudo confirmar el pago.",
                        ) + " Si reintentás no se cobra dos veces.",
                    )
                }
            }
        }
    }

    private fun validateCard(s: SubscriptionUiState): Map<String, String> = buildMap {
        if (s.cardName.isBlank()) put("nombre", "Poné el nombre como figura en la tarjeta.")

        val digits = s.cardNumber.filter(Char::isDigit)
        if (digits.length < 13) put("numero", "El número está incompleto.")

        if (!EXPIRY_REGEX.matches(s.cardExpiry)) {
            put("vencimiento", "Usá el formato MM/AA.")
        } else if (s.cardExpiry.take(2).toInt() !in 1..12) {
            // El regex acepta "19/28"; el mes no existe y el backend lo
            // rechazaría después de un viaje de ida y vuelta.
            put("vencimiento", "El mes no existe.")
        }

        if (s.cardCvc.length < 3) put("cvc", "El código son 3 o 4 dígitos.")
    }

    private companion object {
        val EXPIRY_REGEX = Regex("""^\d{2}/\d{2}$""")
        const val FALLBACK_EMAIL = "sin-email@bikefinder.com"
    }
}

/**
 * Tokeniza la tarjeta para el modo stub del backend.
 *
 * Lo que importa es lo que **no** hace: el número completo no sale del teléfono.
 * Contra Mercado Pago real esto se reemplaza por su SDK, que tokeniza del lado
 * del cliente por la misma razón.
 *
 * El backend en stub aprueba cualquier token salvo `reject-token`, que es lo que
 * devuelven las tarjetas terminadas en 0002 — así se prueba el camino del
 * rechazo sin tocar el servidor.
 */
internal fun cardTokenFor(digits: String, cvc: String): String =
    if (digits.endsWith("0002")) "reject-token" else "tok_${digits.takeLast(4)}_$cvc"

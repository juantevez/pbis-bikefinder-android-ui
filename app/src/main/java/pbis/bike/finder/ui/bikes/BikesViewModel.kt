package pbis.bike.finder.ui.bikes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

/**
 * Qué bicicletas se pueden dar de baja.
 *
 * `deactivate()` es la única transición que el backend permite desde cualquier
 * estado, y eso es deliberado: a alguien a quien le robaron la bici no le puede
 * quedar el registro colgado para siempre, porque `STOLEN` no admite ninguna
 * otra edición. Por eso la regla es más laxa que la de denunciar o editar
 * componentes, que exigen `ACTIVE`.
 *
 * Una ya vendida o inactiva no entra: dar de baja lo que ya está de baja no hace
 * nada y el servidor lo rechaza. Ésta es la regla que antes vivía en
 * `BikeAction.Sell`, cuando la baja era una tarjeta del dashboard.
 */
internal fun BicycleSummaryDto.puedeDarseDeBaja(): Boolean =
    status == BicycleStatus.ACTIVE || status == BicycleStatus.STOLEN

data class BikesUiState(
    val loading: Boolean = true,
    val bikes: List<BicycleSummaryDto> = emptyList(),
    val error: String? = null,
    val canRetry: Boolean = false,
    val userName: String? = null,
    val userEmail: String? = null,

    /** La baja en curso, para no mandarla dos veces desde el mismo gesto. */
    val deregistering: Boolean = false,
    val deregisterError: String? = null,
    /** Aviso de baja hecha; la pantalla lo muestra y lo limpia. */
    val deregistered: String? = null,
)

@HiltViewModel
class BikesViewModel @Inject constructor(
    private val bicycleRepository: BicycleRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BikesUiState())
    val state: StateFlow<BikesUiState> = _state.asStateFlow()

    init {
        loadProfile()
    }

    /**
     * Recarga el listado.
     *
     * La llama la pantalla en cada `onResume` y no el `init`: el ViewModel
     * sobrevive a la navegación —está atado a la entrada del back stack—, así que
     * al volver de registrar una bici el `init` ya corrió y la lista mostraría el
     * estado viejo, sin la bici recién creada.
     */
    fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            when (val result = bicycleRepository.myBicycles()) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, bikes = result.data, error = null)
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        error = result.toUserMessage("No se pudieron cargar tus bicicletas."),
                        // Un GET no duplica nada, pero igual se consulta en vez de
                        // asumirlo: la misma regla vale para el resto de las
                        // pantallas, donde reintentar sí puede escribir dos veces.
                        canRetry = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    /**
     * Nombre y mail del usuario para el encabezado y el avatar.
     *
     * Salen del cache que dejó el login, así que normalmente no cuestan una
     * request. Si falla, no se muestra error: es decoración, y romper la
     * pantalla de bicicletas porque no se pudo saludar al usuario sería absurdo.
     */
    private fun loadProfile() {
        viewModelScope.launch {
            val result = authRepository.profile()
            if (result is ApiResult.Success) {
                _state.update {
                    it.copy(
                        userName = result.data.fullName ?: result.data.email,
                        userEmail = result.data.email,
                    )
                }
            }
        }
    }

    /**
     * Da de baja una bicicleta y vuelve a pedir el listado.
     *
     * La baja vivía en el dashboard, como una tarjeta que abría un selector de
     * bicis. Ahora se dispara deslizando la fila de la bici, o desde su detalle.
     * Lo que se ahorra no es un tap: es tener que reconocer la bici correcta en
     * una lista de nombres parecidos después de haber salido de la lista donde
     * ya se la estaba mirando.
     *
     * Se recarga el listado en vez de sacar la bici a mano: el estado lo tiene el
     * backend, y una lista editada por su cuenta queda mintiendo si el `DELETE`
     * no hizo lo que la pantalla supuso.
     */
    fun deregister(bicycleId: String) {
        if (_state.value.deregistering) return

        _state.update { it.copy(deregistering = true, deregisterError = null) }

        viewModelScope.launch {
            when (val result = bicycleRepository.deregister(bicycleId)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(deregistering = false, deregistered = "Bicicleta dada de baja.")
                    }
                    load()
                }

                else -> _state.update {
                    it.copy(
                        deregistering = false,
                        deregisterError = result.toUserMessage(
                            "No se pudo dar de baja la bicicleta.",
                        ),
                    )
                }
            }
        }
    }

    fun onDeregisteredShown() = _state.update { it.copy(deregistered = null) }

    fun dismissDeregisterError() = _state.update { it.copy(deregisterError = null) }
}

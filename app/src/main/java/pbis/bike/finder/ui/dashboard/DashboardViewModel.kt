package pbis.bike.finder.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.DashboardRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class DashboardUiState(
    val loadingSummary: Boolean = true,
    val totalBicicletas: Int? = null,
    val totalComponentes: Int? = null,
    val totalReportesActivos: Int? = null,
    val bicicletas: List<BicicletaResumenDto> = emptyList(),
    /**
     * Error del resumen **solamente**. No apaga la grilla: registrar una bici o
     * ver el listado no dependen del agregador, y dejar la pantalla en blanco
     * porque no se pudieron pintar cuatro números deja al usuario sin ninguna
     * puerta de salida.
     */
    val summaryError: String? = null,
    val canRetrySummary: Boolean = false,
    val userName: String? = null,
    val userEmail: String? = null,

    /** La baja en curso, para no mandarla dos veces desde el mismo tap. */
    val deregistering: Boolean = false,
    val deregisterError: String? = null,
    /** Aviso de baja hecha; la pantalla lo muestra y lo limpia. */
    val deregistered: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dashboardRepository: DashboardRepository,
    private val bicycleRepository: BicycleRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        loadProfile()
    }

    /**
     * Recarga el resumen.
     *
     * La llama la pantalla en cada `onResume`, no el `init`: el ViewModel
     * sobrevive a la navegación, así que al volver de registrar una bici el
     * `init` ya corrió y los números mostrarían el estado anterior.
     */
    fun loadSummary() {
        _state.update { it.copy(loadingSummary = true, summaryError = null) }

        viewModelScope.launch {
            when (val result = dashboardRepository.summary()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingSummary = false,
                        totalBicicletas = result.data.totalBicicletas,
                        totalComponentes = result.data.totalComponentes,
                        totalReportesActivos = result.data.totalReportesActivos,
                        bicicletas = result.data.bicicletas,
                        summaryError = null,
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingSummary = false,
                        summaryError = result.toUserMessage("No se pudo cargar tu resumen."),
                        canRetrySummary = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    /**
     * Nombre y mail del encabezado. Salen del cache del login, así que
     * normalmente no cuestan una request. Si falla no se muestra error: es
     * decoración.
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
     * Da de baja una bicicleta y vuelve a pedir el resumen.
     *
     * El resumen se recarga en vez de sacar la bici de la lista a mano: los tres
     * números de arriba también cambian con la baja, y mantenerlos a mano acá
     * sería replicar la cuenta que ya hace el agregador.
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
                    loadSummary()
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

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}

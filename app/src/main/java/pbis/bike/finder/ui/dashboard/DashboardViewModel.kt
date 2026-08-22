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
import pbis.bike.finder.data.repository.DashboardRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class DashboardUiState(
    val loadingSummary: Boolean = true,
    /**
     * Lo único que se usa del resumen: alimenta los selectores de bicicleta.
     * Los contadores que traía el agregador —bicicletas, componentes, reportes
     * activos— no se mapean más porque la tira de números que los mostraba dejó
     * de existir. El DTO los sigue parseando: son parte de lo que el endpoint
     * devuelve, y eso no lo decide esta pantalla.
     */
    val bicicletas: List<BicicletaResumenDto> = emptyList(),
    /**
     * Error del resumen **solamente**. No apaga la grilla: registrar una bici o
     * ver el listado no dependen del agregador, y dejar la pantalla en blanco
     * porque no se pudo traer una lista deja al usuario sin ninguna puerta de
     * salida.
     */
    val summaryError: String? = null,
    val canRetrySummary: Boolean = false,
    val userName: String? = null,
    val userEmail: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dashboardRepository: DashboardRepository,
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
     * `init` ya corrió y los selectores mostrarían la lista anterior —sin la
     * bici recién registrada.
     */
    fun loadSummary() {
        _state.update { it.copy(loadingSummary = true, summaryError = null) }

        viewModelScope.launch {
            when (val result = dashboardRepository.summary()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingSummary = false,
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
}

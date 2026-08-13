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
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class BikesUiState(
    val loading: Boolean = true,
    val bikes: List<BicycleSummaryDto> = emptyList(),
    val error: String? = null,
    val canRetry: Boolean = false,
    val userName: String? = null,
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
     * Nombre del usuario para el encabezado.
     *
     * Sale del cache que dejó el login, así que normalmente no cuesta una
     * request. Si falla, no se muestra error: es decoración, y romper la
     * pantalla de bicicletas porque no se pudo saludar al usuario sería absurdo.
     */
    private fun loadProfile() {
        viewModelScope.launch {
            val result = authRepository.profile()
            if (result is ApiResult.Success) {
                _state.update { it.copy(userName = result.data.fullName ?: result.data.email) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}

package pbis.bike.finder.ui.tips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatsDto
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class TipsListUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val canRetry: Boolean = false,
    val tips: List<TipDto> = emptyList(),
    /**
     * El resumen de arriba. Puede faltar aunque las pistas hayan cargado: son
     * dos endpoints distintos y el contador es lo prescindible de los dos.
     */
    val stats: TipStatsDto? = null,
)

/**
 * Las pistas de una denuncia — `tips-list.html`.
 *
 * Es lo que llega cuando alguien escanea el QR del cartel público: gente que
 * dice haber visto la bici. Por eso el orden y el estado importan más que en un
 * listado cualquiera — una pista sin leer puede ser la bici.
 */
@HiltViewModel
class TipsListViewModel @Inject constructor(
    private val theftRepository: TheftRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TipsListUiState())
    val state: StateFlow<TipsListUiState> = _state.asStateFlow()

    private var reportId: String? = null

    fun start(reportId: String) {
        if (this.reportId != null) return
        this.reportId = reportId
        load()
    }

    /**
     * Pistas y contadores en paralelo, como el `Promise.all` de la web.
     *
     * Con una diferencia: allá, si fallaba cualquiera de las dos, la pantalla
     * entera mostraba un error. Acá el listado manda — que no se pueda pintar la
     * barra de contadores no es motivo para esconder las pistas, que son el
     * contenido real.
     */
    fun load() {
        val id = reportId ?: return
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val tipsCall = async { theftRepository.tips(id) }
            val statsCall = async { theftRepository.tipStats(id) }

            val tipsResult = tipsCall.await()
            val statsResult = statsCall.await()

            when (tipsResult) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        // Las nuevas primero y, dentro de cada grupo, las más
                        // recientes arriba: es el orden en que hay que mirarlas.
                        tips = tipsResult.data.sortedWith(
                            compareByDescending<TipDto> { tip -> tip.isUnread }
                                .thenByDescending { tip -> tip.submittedAt },
                        ),
                        stats = (statsResult as? ApiResult.Success)?.data,
                    )
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        error = tipsResult.toUserMessage("No se pudieron cargar las pistas."),
                        canRetry = tipsResult.isSafeToRetry(),
                    )
                }
            }
        }
    }
}
